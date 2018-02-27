/**
 * Copyright (C) 2016-2018 Lightbend Inc. <http://www.lightbend.com/>
 */

package akka.persistence.typed.internal

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.NoSerializationVerificationNeeded
import akka.actor.typed.Behavior
import akka.actor.typed.Behavior.StoppedBehavior
import akka.actor.typed.scaladsl.{ ActorContext, TimerScheduler }
import akka.annotation.InternalApi
import akka.event.{ LogSource, Logging }
import akka.persistence.typed.scaladsl.PersistentBehaviors
import akka.persistence.{ JournalProtocol, Persistence, RecoveryPermitter, SnapshotProtocol }
import akka.{ actor ⇒ a }

/** INTERNAL API */
@InternalApi
private[akka] object EventsourcedBehavior {

  // ok to wrap around (2*Int.MaxValue restarts will not happen within a journal roundtrip)
  private[akka] val instanceIdCounter = new AtomicInteger(1)

  @InternalApi private[akka] object WriterIdentity {
    def newIdentity(): WriterIdentity = {
      val instanceId: Int = EventsourcedBehavior.instanceIdCounter.getAndIncrement()
      val writerUuid: String = UUID.randomUUID.toString
      WriterIdentity(instanceId, writerUuid)
    }
  }
  @InternalApi private[akka] final case class WriterIdentity(instanceId: Int, writerUuid: String)

  /** INTERNAL API: Protocol used internally by the eventsourced behaviors, never exposed to user-land */
  @InternalApi private[akka] sealed trait EventsourcedProtocol
  @InternalApi private[akka] case object RecoveryPermitGranted extends EventsourcedProtocol
  @InternalApi private[akka] final case class JournalResponse(msg: akka.persistence.JournalProtocol.Response) extends EventsourcedProtocol
  @InternalApi private[akka] final case class SnapshotterResponse(msg: akka.persistence.SnapshotProtocol.Response) extends EventsourcedProtocol
  @InternalApi private[akka] final case class RecoveryTickEvent(snapshot: Boolean) extends EventsourcedProtocol
  @InternalApi private[akka] final case class ReceiveTimeout(timeout: akka.actor.ReceiveTimeout) extends EventsourcedProtocol

  implicit object PersistentBehaviorLogSource extends LogSource[EventsourcedBehavior[_, _, _]] {
    override def genString(b: EventsourcedBehavior[_, _, _]): String = {
      val behaviorShortName = b match {
        case _: EventsourcedRunning[_, _, _]                  ⇒ "running"
        case _: EventsourcedRecoveringEvents[_, _, _]         ⇒ "recover-evts"
        case _: EventsourcedRecoveringSnapshot[_, _, _]       ⇒ "recover-snap"
        case _: EventsourcedRequestingRecoveryPermit[_, _, _] ⇒ "awaiting-permit"
      }
      s"PersistentBehavior[id:${b.persistenceId}][${b.context.self.path}][$behaviorShortName]"
    }
  }

}

trait EventsourcedBehavior[Command, Event, State] {
  import EventsourcedBehavior._
  import akka.actor.typed.scaladsl.adapter._

  def context: ActorContext[Any]
  def timers: TimerScheduler[Any]

  type C = Command
  type AC = ActorContext[C]
  type E = Event
  type S = State

  // used for signaling intent in type signatures
  type SeqNr = Long

  // FIXME make this not even visible in the state that is "running", we'll never call it there again
  def persistenceId: String

  def initialState: State
  def commandHandler: PersistentBehaviors.CommandHandler[Command, Event, State]
  def eventHandler: (State, Event) ⇒ State

  // FIXME make this not even visible in the state that is "running", we'll never call it there again
  def recoveryCompleted: (ActorContext[Command], State) ⇒ Unit
  def snapshotWhen: (State, Event, SeqNr) ⇒ Boolean

  def tagger: Event ⇒ Set[String]
  def journalPluginId: String
  def snapshotPluginId: String

  // ------ common -------

  // FIXME make all things protected

  lazy val extension = Persistence(context.system.toUntyped)
  lazy val journal: a.ActorRef = extension.journalFor(journalPluginId)
  lazy val snapshotStore: a.ActorRef = extension.snapshotStoreFor(snapshotPluginId)

  protected lazy val selfUntyped: a.ActorRef = context.self.toUntyped
  protected lazy val selfUntypedAdapted: a.ActorRef = context.messageAdapter[Any] {
    case res: JournalProtocol.Response           ⇒ JournalResponse(res)
    case RecoveryPermitter.RecoveryPermitGranted ⇒ RecoveryPermitGranted
    case res: SnapshotProtocol.Response          ⇒ SnapshotterResponse(res)
    case cmd: Command @unchecked                 ⇒ cmd // if it was wrong, we'll realise when trying to onMessage the cmd
  }.toUntyped

}
