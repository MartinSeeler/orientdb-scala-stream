package orientdb.streams.impl

import java.util.concurrent.Semaphore

import akka.actor.{ Actor, ActorRef }
import orientdb.streams.impl.ActorControlledResultListener.{Finish, GiveMeListener, Release}

private object ActorControlledResultListener {
  sealed trait Message
  case object Release extends Message
  case object GiveMeListener extends Message
  case object Finish extends Message
}

private class ActorControlledResultListener(sourceRef: ActorRef) extends Actor {
  val semaphore = new Semaphore(0)
  val listener = new BlockingOCommandResultListener(sourceRef, semaphore)

  def receive = {
    case Release        ⇒ semaphore.release()
    case GiveMeListener ⇒ sender() ! listener
    case Finish =>
      listener.finishFetching()
      context.stop(self)
  }
}