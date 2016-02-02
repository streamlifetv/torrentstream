package com.karasiq.bittorrent.format

import akka.util.ByteString

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer

case class TorrentPiece(size: Long, sha1: ByteString, file: TorrentFileInfo)

object TorrentPiece {
  def sequence(files: TorrentFiles): IndexedSeq[TorrentPiece] = {
    // Total torrent size
    val totalSize = files.files.map(_.size).sum

    @tailrec
    def pieceSequenceRec(buffer: ArrayBuffer[TorrentPiece], offset: Long, fileOffset: Long, pieceIndex: Int, fileSeq: Seq[TorrentFileInfo]): IndexedSeq[TorrentPiece] = fileSeq match {
      case Seq(currentFile, fs @ _*) if fileOffset >= currentFile.size ⇒
        pieceSequenceRec(buffer, offset, 0L, pieceIndex, fs)

      case fs @ Seq(currentFile, _*) if offset < totalSize ⇒
        val sha1 = files.pieces.slice(pieceIndex * 20, (pieceIndex * 20) + 20)
        val piece = TorrentPiece(files.pieceLength, sha1, currentFile)
        pieceSequenceRec(buffer :+ piece, offset + files.pieceLength, fileOffset + files.pieceLength, pieceIndex + 1, fs)

      case other ⇒
        buffer.result()
    }
    pieceSequenceRec(new ArrayBuffer[TorrentPiece](files.pieces.length / 20), 0L, 0L, 0, files.files)
  }
}