package com.karasiq.bittorrent.protocol

import java.io.IOException
import java.nio.ByteBuffer
import java.security._
import java.util.concurrent.TimeoutException
import javax.crypto.KeyAgreement
import javax.crypto.spec.{DHParameterSpec, DHPublicKeySpec}

import akka.event.LoggingAdapter
import akka.stream._
import akka.stream.stage._
import akka.util.ByteString
import org.bouncycastle.crypto.engines.RC4Engine
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.jcajce.provider.asymmetric.dh.BCDHPublicKey

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Try

private[protocol] object PeerStreamEncryption {
  private val provider = new org.bouncycastle.jce.provider.BouncyCastleProvider()

  private val p = BigInt("FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A63A36210000000000090563", 16)
  private val g = BigInt(2)

  private val generator = {
    val generator = KeyPairGenerator.getInstance("DH", provider)
    generator.initialize(new DHParameterSpec(p.underlying(), g.underlying(), 160))
    generator
  }

  @tailrec
  def generateKey(): KeyPair = {
    val kp = generator.generateKeyPair()
    if (kp.getPublic.asInstanceOf[BCDHPublicKey].getY.toByteArray.length == 96) {
      kp
    } else {
      // Retry
      generateKey()
    }
  }

  def readKey(bytes: ByteString): Option[PublicKey] = {
    val generator = KeyFactory.getInstance("DH", provider)
    Try(generator.generatePublic(new DHPublicKeySpec(BigInt(bytes.toArray).underlying(), p.underlying(), g.underlying()))).toOption
  }

  def sha1(data: ByteString): ByteString = {
    val md = MessageDigest.getInstance("SHA-1", provider)
    ByteString(md.digest(data.toArray))
  }
}

/**
  * Peer stream encryption stage
  * @param infoHash Torrent 20 bytes info hash
  * @param log Logging adapter
  * @see [[https://wiki.vuze.com/w/Message_Stream_Encryption]]
  */
class PeerStreamEncryption(infoHash: ByteString)(implicit log: LoggingAdapter) extends GraphStage[BidiShape[ByteString, ByteString, ByteString, ByteString]] {
  // TODO: Server mode
  import PeerStreamEncryption.{readKey, sha1}

  val tcpInput: Inlet[ByteString] = Inlet("TcpInput")
  val messageInput: Inlet[ByteString] = Inlet("MessageInput")
  val messageOutput: Outlet[ByteString] = Outlet("MessageOutput")
  val tcpOutput: Outlet[ByteString] = Outlet("TcpOutput")

  override def shape: BidiShape[ByteString, ByteString, ByteString, ByteString] = {
    BidiShape(tcpInput, messageOutput, messageInput, tcpOutput)
  }

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {
    object Stage extends Enumeration {
      val CLIENT_DH, CLIENT_AWAIT_DH, CLIENT_AWAIT_CONFIRMATION = Value
      val SERVER_AWAIT_DH, SERVER_AWAIT_CONFIRMATION = Value
      val READY = Value
    }

    var stage = Stage.CLIENT_DH
    var rc4Enabled = false

    var messageInputBuffer = Vector.empty[ByteString]
    var tcpInputBuffer = ByteString.empty // Input buffer

    val key = PeerStreamEncryption.generateKey()
    val dh = KeyAgreement.getInstance("DH", PeerStreamEncryption.provider)
    dh.init(key.getPrivate)

    val ownRc4Engine = new RC4Engine
    val peerRc4Engine = new RC4Engine
    val secureRandom = new SecureRandom(key.getPublic.getEncoded)

    var secret = ByteString.empty // Diffie-Hellman shared secret
    val vc = ByteString(0, 0, 0, 0, 0, 0, 0, 0) // Verification constant, 8 bytes

    def randomPadding: ByteString = {
      secureRandom.nextBytes(rc4InBuffer)
      ByteString(rc4InBuffer).take(secureRandom.nextInt(512))
    }

    val rc4InBuffer = new Array[Byte](1024)
    val rc4OutBuffer = new Array[Byte](1024)

    def rc4(engine: RC4Engine, data: ByteString): ByteString = {
      val input = data.toByteBuffer
      while (input.remaining() > 0) {
        val length = Array(input.remaining(), 1024).min
        input.get(rc4InBuffer, 0, length)
        engine.processBytes(rc4InBuffer, 0, length, rc4OutBuffer, 0)
        input.position(input.position() - length)
        input.put(rc4OutBuffer, 0, length)
      }
      input.flip()
      ByteString(input)
    }

    def rc4Encrypt(data: ByteString): ByteString = {
      rc4(ownRc4Engine, data)
    }

    def rc4Decrypt(data: ByteString): ByteString = {
      rc4(peerRc4Engine, data)
    }

    def resetRc4(engine: RC4Engine): Unit = {
      engine.reset()
      engine.processBytes(rc4InBuffer, 0, 1024, rc4OutBuffer, 0)
    }

    def sendPublicKey(): Unit = {
      val bytes = ByteString(key.getPublic.asInstanceOf[BCDHPublicKey].getY.toByteArray)
      emit(tcpOutput, bytes ++ randomPadding)
      stage = Stage.CLIENT_AWAIT_DH
    }

    def clientStage2(): Unit = {
      if (tcpInputBuffer.length >= 96 && messageInputBuffer.nonEmpty) {
        val (take, keep) = tcpInputBuffer.splitAt(96)
        tcpInputBuffer = keep
        readKey(take) match {
          case Some(bKey) ⇒
            val handshake: ByteString = {
              if (messageInputBuffer.nonEmpty) {
                val handshake = messageInputBuffer.head
                messageInputBuffer = messageInputBuffer.tail
                handshake
              } else {
                ByteString.empty
              }
            }
            dh.doPhase(bKey, true)
            secret = ByteString(dh.generateSecret())
            ownRc4Engine.init(true, new KeyParameter(sha1(ByteString("keyA") ++ secret ++ infoHash).toArray))
            peerRc4Engine.init(false, new KeyParameter(sha1(ByteString("keyB") ++ secret ++ infoHash).toArray))
            resetRc4(ownRc4Engine)
            resetRc4(peerRc4Engine)
            val hash1 = sha1(ByteString("req1") ++ secret)
            val hash2 = {
              val array = sha1(ByteString("req2") ++ infoHash).toArray
              val xor = sha1(ByteString("req3") ++ secret).toArray
              for (i <- array.indices) array(i) = (array(i) ^ xor(i)).toByte
              ByteString(array)
            }
            val encrypted = {
              val cryptoProvide = 1 | 2
              val pad = randomPadding
              val buffer = ByteBuffer.allocate(vc.length + 4 + 2 + pad.length + 2 + handshake.length)
              buffer.put(vc.toByteBuffer)
              buffer.putInt(cryptoProvide)
              buffer.putShort(pad.length.toShort)
              buffer.put(pad.toByteBuffer)
              buffer.putShort(handshake.length.toShort)
              buffer.put(handshake.toByteBuffer)
              buffer.flip()
              rc4Encrypt(ByteString(buffer))
            }
            emit(tcpOutput, hash1 ++ hash2 ++ encrypted)
            stage = Stage.CLIENT_AWAIT_CONFIRMATION

          case None ⇒
            failStage(new IOException("Invalid DH key"))
        }
      }
    }

    def clientStage3(): Unit = {
      @tailrec
      def syncVcPos(): Boolean = {
        if (tcpInputBuffer.length < vc.length) {
          false
        } else {
          val (take, keep) = tcpInputBuffer.splitAt(vc.length)
          if (rc4Decrypt(take) == vc) {
            tcpInputBuffer = keep
            true
          } else {
            tcpInputBuffer = tcpInputBuffer.tail
            resetRc4(peerRc4Engine)
            syncVcPos()
          }
        }
      }

      if (syncVcPos()) {
        val cryptoSelect = BitTorrentTcpProtocol.int32FromBytes(rc4Decrypt(tcpInputBuffer.take(4)))
        val padLength = BitTorrentTcpProtocol.int32FromBytes(rc4Decrypt(tcpInputBuffer.drop(4).take(2)))
        rc4Decrypt(tcpInputBuffer.drop(4 + 2).take(padLength))
        tcpInputBuffer = tcpInputBuffer.drop(4 + 2 + padLength)
        if ((cryptoSelect & 2) != 0) {
          rc4Enabled = true
        } else if ((cryptoSelect & 1) != 0) {
          rc4Enabled = false
        } else {
          failStage(new IOException("No known encryption methods available"))
        }
        log.debug("Peer message stream encryption mode set to {}", if (rc4Enabled) "RC4" else "plaintext")
        stage = Stage.READY
        emit(messageOutput, if (rc4Enabled) rc4Decrypt(tcpInputBuffer) else tcpInputBuffer)
        tcpInputBuffer = ByteString.empty
      }
    }

    setHandler(tcpInput, new InHandler {
      override def onPush(): Unit = {
        val bytes = grab(tcpInput)
        if (tcpInputBuffer.length > 614400) {
          failStage(new IOException("Buffer overflow"))
        }
        tcpInputBuffer ++= bytes
        stage match {
          case Stage.CLIENT_DH ⇒
            // Nothing

          case Stage.CLIENT_AWAIT_DH ⇒
            clientStage2()
            pull(tcpInput)

          case Stage.CLIENT_AWAIT_CONFIRMATION ⇒
            clientStage3()
            pull(tcpInput)

          case Stage.READY ⇒
            emit(messageOutput, if (rc4Enabled) rc4Decrypt(tcpInputBuffer) else tcpInputBuffer, () ⇒ if (!hasBeenPulled(tcpInput)) tryPull(tcpInput))
            tcpInputBuffer = ByteString.empty
        }
      }
    })

    setHandler(tcpOutput, new OutHandler {
      override def onPull(): Unit = {
        if (stage == Stage.CLIENT_DH) {
          sendPublicKey()
          pull(tcpInput)
        } else if (!hasBeenPulled(messageInput)) {
          pull(messageInput)
        }
      }
    })

    setHandler(messageInput, new InHandler {
      override def onPush(): Unit = {
        if (stage != Stage.READY || !isAvailable(tcpOutput)) {
          messageInputBuffer :+= grab(messageInput)
        } else {
          emitMultiple(tcpOutput, (messageInputBuffer :+ grab(messageInput)).map(msg ⇒ if (rc4Enabled) rc4Encrypt(msg) else msg), () ⇒ if (!hasBeenPulled(messageInput)) tryPull(messageInput))
          messageInputBuffer = Vector.empty
        }
      }
    })

    setHandler(messageOutput, new OutHandler {
      override def onPull(): Unit = {
        if (stage == Stage.READY && !hasBeenPulled(tcpInput)) {
          pull(tcpInput)
        }
      }
    })

    override protected def onTimer(timerKey: Any): Unit = {
      if (stage != Stage.READY) {
        failStage(new TimeoutException("Handshake timeout"))
      }
    }

    override def preStart(): Unit = {
      super.preStart()
      scheduleOnce("HandshakeTimeout", 30 seconds)
    }
  }
}
