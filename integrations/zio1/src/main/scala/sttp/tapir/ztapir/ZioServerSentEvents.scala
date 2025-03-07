package sttp.tapir.ztapir

import zio.stream.{Stream, ZTransducer}
import sttp.model.sse.ServerSentEvent
import zio.Chunk

object ZioServerSentEvents {
  def serialiseSSEToBytes: Stream[Throwable, ServerSentEvent] => Stream[Throwable, Byte] = sseStream => {
    sseStream
      .map(sse => {
        s"${sse.toString()}\n\n"
      })
      .mapConcatChunk(s => Chunk.fromArray(s.getBytes("UTF-8")))
  }

  def parseBytesToSSE: Stream[Throwable, Byte] => Stream[Throwable, ServerSentEvent] = stream => {
    stream
      .aggregate(ZTransducer.utf8Decode)
      .aggregate(ZTransducer.splitLines)
      .mapAccum(List.empty[String]) { case (acc, line) =>
        if (line.isEmpty) (Nil, Some(acc.reverse))
        else (line :: acc, None)
      }
      .collect { case Some(l) =>
        l
      }
      .filter(_.nonEmpty)
      .map(ServerSentEvent.parse)
  }
}
