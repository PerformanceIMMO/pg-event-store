package eventstore

import eventstore.EventRepository.Error.Unexpected
import eventstore.EventRepository.SaveEventError
import eventstore.EventRepository.Subscription
import eventstore.SwitchableZStream.Message
import eventstore.types.AggregateName
import eventstore.types.AggregateVersion
import eventstore.types.EventStoreVersion
import eventstore.types.EventStreamId
import zio.IO
import zio.Scope
import zio.Tag
import zio.UIO
import zio.ZIO
import zio.stream.Stream
import zio.stream.ZStream

object EventRepository {

  trait Subscription[EventType, DoneBy] {
    def restartFromFirstEvent(lastEventToHandle: EventStoreVersion): UIO[Unit]
    def stream: ZStream[Scope, Unexpected, EventStoreEvent[EventType, DoneBy]]
  }

  object Subscription {
    def fromSwitchableStream[EventType, DoneBy](
        switchableStream: SwitchableZStream[Any, Unexpected, RepositoryEvent[EventType, DoneBy]]
    ): Subscription[EventType, DoneBy] =
      new Subscription[EventType, DoneBy] {
        override def restartFromFirstEvent(lastEventToHandle: EventStoreVersion): UIO[Unit] =
          switchableStream.switchToSecondUntil(_.eventStoreVersion == lastEventToHandle)

        override def stream: ZStream[Scope, Unexpected, EventStoreEvent[EventType, DoneBy]] =
          switchableStream.stream.collect {
            case Message.SwitchedToSecond => Reset[EventType, DoneBy]()
            case Message.Event(a)         => a
          }
      }

  }

  sealed trait SaveEventError

  sealed trait Error
  object Error {
    case class VersionConflict(provided: AggregateVersion, required: AggregateVersion) extends Error with SaveEventError
    case class Unexpected(throwable: Throwable) extends Error with SaveEventError
  }

  implicit class EventsOps[E, DoneBy](self: Seq[RepositoryWriteEvent[E, DoneBy]]) {

    def checkVersionsAreContiguousIncrements: IO[Unexpected, Unit] = self match {
      case _ :: tail =>
        ZIO.foreachDiscard(self.zip(tail)) { case (current, next) =>
          ZIO
            .fail(
              Unexpected(
                new IllegalArgumentException(
                  s"Invalid version sequence current: ${current.aggregateVersion}, next: ${next.aggregateVersion}"
                )
              )
            )
            .unless(current.aggregateVersion.next == next.aggregateVersion)
        }
      case _ => ZIO.unit
    }
  }
}

trait EventRepository[Encoder[_], Decoder[_]] {

  def getAllEvents[A: Encoder: Tag, DoneBy: Encoder: Tag]: Stream[Unexpected, RepositoryEvent[A, DoneBy]]

  def listEventStreamWithName(aggregateName: AggregateName): Stream[Unexpected, EventStreamId]

  def getEventStream[A: Encoder: Tag, DoneBy: Encoder: Tag](
      eventStreamId: EventStreamId
  ): IO[Unexpected, Seq[RepositoryEvent[A, DoneBy]]]

  def saveEvents[A: Encoder: Decoder: Tag, DoneBy: Encoder: Decoder: Tag](
      eventStreamId: EventStreamId,
      events: Seq[RepositoryWriteEvent[A, DoneBy]]
  ): IO[SaveEventError, Seq[RepositoryEvent[A, DoneBy]]]

  def listen[EventType: Encoder: Tag, DoneBy: Encoder: Tag]: ZIO[Scope, Unexpected, Subscription[EventType, DoneBy]]

}
