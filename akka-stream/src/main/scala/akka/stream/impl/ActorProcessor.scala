/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import java.util.Arrays
import akka.actor._
import akka.stream.ActorFlowMaterializerSettings
import akka.stream.actor.ActorSubscriber.OnSubscribe
import akka.stream.actor.ActorSubscriberMessage.{ OnNext, OnComplete, OnError }
import org.reactivestreams.{ Subscriber, Subscription, Processor }
import akka.event.Logging

/**
 * INTERNAL API
 */
private[akka] object ActorProcessor {

  def apply[I, O](impl: ActorRef): ActorProcessor[I, O] = {
    val p = new ActorProcessor[I, O](impl)
    impl ! ExposedPublisher(p.asInstanceOf[ActorPublisher[Any]])
    p
  }
}

/**
 * INTERNAL API
 */
private[akka] class ActorProcessor[I, O](impl: ActorRef) extends ActorPublisher[O](impl)
  with Processor[I, O] {
  override def onSubscribe(s: Subscription): Unit = impl ! OnSubscribe(s)
  override def onError(t: Throwable): Unit = impl ! OnError(t)
  override def onComplete(): Unit = impl ! OnComplete
  override def onNext(t: I): Unit = impl ! OnNext(t)
}

/**
 * INTERNAL API
 */
private[akka] abstract class BatchingInputBuffer(val size: Int, val pump: Pump) extends DefaultInputTransferStates {
  require(size > 0, "buffer size cannot be zero")
  require((size & (size - 1)) == 0, "buffer size must be a power of two")
  // TODO: buffer and batch sizing heuristics
  private var upstream: Subscription = _
  private val inputBuffer = Array.ofDim[AnyRef](size)
  private var inputBufferElements = 0
  private var nextInputElementCursor = 0
  private var upstreamCompleted = false
  private val IndexMask = size - 1

  private def requestBatchSize = math.max(1, inputBuffer.length / 2)
  private var batchRemaining = requestBatchSize

  override def toString: String =
    s"BatchingInputBuffer(size=$size, elems=$inputBufferElements, completed=$upstreamCompleted, remaining=$batchRemaining)"

  override val subreceive: SubReceive = new SubReceive(waitingForUpstream)

  override def dequeueInputElement(): Any = {
    val elem = inputBuffer(nextInputElementCursor)
    inputBuffer(nextInputElementCursor) = null

    batchRemaining -= 1
    if (batchRemaining == 0 && !upstreamCompleted) {
      upstream.request(requestBatchSize)
      batchRemaining = requestBatchSize
    }

    inputBufferElements -= 1
    nextInputElementCursor += 1
    nextInputElementCursor &= IndexMask
    elem
  }

  protected final def enqueueInputElement(elem: Any): Unit = {
    if (isOpen) {
      if (inputBufferElements == size) throw new IllegalStateException("Input buffer overrun")
      inputBuffer((nextInputElementCursor + inputBufferElements) & IndexMask) = elem.asInstanceOf[AnyRef]
      inputBufferElements += 1
    }
    pump.pump()
  }

  override def cancel(): Unit = {
    if (!upstreamCompleted) {
      upstreamCompleted = true
      if (upstream ne null) upstream.cancel()
      clear()
    }
  }
  override def isClosed: Boolean = upstreamCompleted

  private def clear(): Unit = {
    Arrays.fill(inputBuffer, 0, inputBuffer.length, null)
    inputBufferElements = 0
  }

  override def inputsDepleted = upstreamCompleted && inputBufferElements == 0
  override def inputsAvailable = inputBufferElements > 0

  protected def onComplete(): Unit = {
    upstreamCompleted = true
    subreceive.become(completed)
    pump.pump()
  }

  protected def onSubscribe(subscription: Subscription): Unit = {
    assert(subscription != null)
    upstream = subscription
    // Prefetch
    upstream.request(inputBuffer.length)
    subreceive.become(upstreamRunning)
  }

  protected def onError(e: Throwable): Unit = {
    upstreamCompleted = true
    subreceive.become(completed)
    inputOnError(e)
  }

  protected def waitingForUpstream: Actor.Receive = {
    case OnComplete                ⇒ onComplete()
    case OnSubscribe(subscription) ⇒ onSubscribe(subscription)
    case OnError(cause)            ⇒ onError(cause)
  }

  protected def upstreamRunning: Actor.Receive = {
    case OnNext(element)           ⇒ enqueueInputElement(element)
    case OnComplete                ⇒ onComplete()
    case OnError(cause)            ⇒ onError(cause)
    case OnSubscribe(subscription) ⇒ subscription.cancel() // spec rule 2.5
  }

  protected def completed: Actor.Receive = {
    case OnSubscribe(subscription) ⇒ throw new IllegalStateException("Cannot subscribe shutdown subscriber") // FIXME "shutdown subscriber"?!
  }

  protected def inputOnError(e: Throwable): Unit = {
    clear()
  }

}

/**
 * INTERNAL API
 */
private[akka] class SimpleOutputs(val actor: ActorRef, val pump: Pump) extends DefaultOutputTransferStates {
  import ReactiveStreamsCompliance._

  protected var exposedPublisher: ActorPublisher[Any] = _

  protected var subscriber: Subscriber[Any] = _
  protected var downstreamDemand: Long = 0L
  protected var downstreamCompleted = false
  override def demandAvailable = downstreamDemand > 0
  override def demandCount: Long = downstreamDemand

  override def subreceive = _subreceive
  private val _subreceive = new SubReceive(waitingExposedPublisher)

  def enqueueOutputElement(elem: Any): Unit = {
    downstreamDemand -= 1
    tryOnNext(subscriber, elem)
  }

  def complete(): Unit = {
    if (!downstreamCompleted) {
      downstreamCompleted = true
      if (exposedPublisher ne null) exposedPublisher.shutdown(None)
      if (subscriber ne null) tryOnComplete(subscriber)
    }
  }

  def cancel(e: Throwable): Unit = {
    if (!downstreamCompleted) {
      downstreamCompleted = true
      if (exposedPublisher ne null) exposedPublisher.shutdown(Some(e))
      if ((subscriber ne null) && !e.isInstanceOf[SpecViolation]) tryOnError(subscriber, e)
    }
  }

  def isClosed: Boolean = downstreamCompleted

  protected def createSubscription(): Subscription = new ActorSubscription(actor, subscriber)

  private def subscribePending(subscribers: Seq[Subscriber[Any]]): Unit =
    subscribers foreach { sub ⇒
      if (subscriber eq null) {
        subscriber = sub
        tryOnSubscribe(subscriber, createSubscription())
      } else
        tryOnError(sub, new IllegalStateException(s"${Logging.simpleName(this)} ${SupportsOnlyASingleSubscriber}"))
    }

  protected def waitingExposedPublisher: Actor.Receive = {
    case ExposedPublisher(publisher) ⇒
      exposedPublisher = publisher
      subreceive.become(downstreamRunning)
    case other ⇒
      throw new IllegalStateException(s"The first message must be ExposedPublisher but was [$other]")
  }

  protected def downstreamRunning: Actor.Receive = {
    case SubscribePending ⇒
      subscribePending(exposedPublisher.takePendingSubscribers())
    case RequestMore(subscription, elements) ⇒
      if (elements < 1) {
        cancel(ReactiveStreamsCompliance.numberOfElementsInRequestMustBePositiveException)
      } else {
        downstreamDemand += elements
        if (downstreamDemand < 1) { // Long has overflown
          cancel(ReactiveStreamsCompliance.totalPendingDemandMustNotExceedLongMaxValueException)
        }

        pump.pump() // FIXME should this be called even on overflow, sounds like a bug to me
      }
    case Cancel(subscription) ⇒
      downstreamCompleted = true
      exposedPublisher.shutdown(Some(new ActorPublisher.NormalShutdownException))
      pump.pump()
  }

}

/**
 * INTERNAL API
 */
private[akka] abstract class ActorProcessorImpl(val settings: ActorFlowMaterializerSettings)
  extends Actor
  with ActorLogging
  with Pump {

  // FIXME: make pump a member
  protected val primaryInputs: Inputs = new BatchingInputBuffer(settings.initialInputBufferSize, this) {
    override def inputOnError(e: Throwable): Unit = ActorProcessorImpl.this.onError(e)
  }

  protected val primaryOutputs: Outputs = new SimpleOutputs(self, this)

  /**
   * Subclass may override [[#activeReceive]]
   */
  final override def receive = new ExposedPublisherReceive(activeReceive, unhandled) {
    override def receiveExposedPublisher(ep: ExposedPublisher): Unit = {
      primaryOutputs.subreceive(ep)
      context become activeReceive
    }
  }

  def activeReceive: Receive = primaryInputs.subreceive orElse primaryOutputs.subreceive

  protected def onError(e: Throwable): Unit = fail(e)

  protected def fail(e: Throwable): Unit = {
    // FIXME: escalate to supervisor
    if (settings.debugLogging)
      log.debug("fail due to: {}", e.getMessage)
    primaryInputs.cancel()
    primaryOutputs.cancel(e)
    context.stop(self)
  }

  override def pumpFinished(): Unit = {
    primaryInputs.cancel()
    primaryOutputs.complete()
    context.stop(self)
  }

  override def pumpFailed(e: Throwable): Unit = fail(e)

  override def postStop(): Unit = {
    primaryInputs.cancel()
    primaryOutputs.cancel(new IllegalStateException("Processor actor terminated abruptly"))
  }

  override def postRestart(reason: Throwable): Unit = {
    super.postRestart(reason)
    throw new IllegalStateException("This actor cannot be restarted", reason)
  }

}