package orientdb.stream.impl

import akka.actor.{ ActorSystem, _ }
import akka.pattern.ask
import akka.stream.actor.ActorPublisher
import akka.util.Timeout
import com.orientechnologies.orient.core.command.OCommandResultListener
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx
import org.reactivestreams.Publisher
import ActorSource.{ Complete, ErrorOccurred }
import orientdb.stream.{ NonBlockingQuery, OrientLoader }
import orientdb.stream.wrappers.SmartOSQLNonBlockingQuery
import ActorSourceWithListener.RegisterListener
import ActorControlledResultListener.GiveMeListener

import scala.concurrent.{ Future, ExecutionContext }
import scala.concurrent.duration._
import scala.reflect.ClassTag

private[stream] class NonBlockingQueryBackpressuring[A: ClassTag](query: String,
    limit: Int,
    fetchPlan: String,
    arguments: scala.collection.immutable.Map[Object, Object]) extends NonBlockingQuery[A] {

  override def execute(params: AnyRef*)(implicit db: ODatabaseDocumentTx,
    actorRefFactory: ActorRefFactory,
    ec: ExecutionContext,
    loader: OrientLoader): Publisher[A] = {

    implicit val timeout = Timeout(3.seconds) // TODO timeout from outside
    val sourceRef = actorRefFactory.actorOf(Props(new ActorSourceWithListener[A]))
    val listenerRef = actorRefFactory.actorOf(Props(new ActorControlledResultListener(sourceRef)))
    def handleErrorAtSource: PartialFunction[Throwable, Unit] = { case t: Throwable ⇒ sourceRef ! ErrorOccurred(t) }

    (for {
      _ ← sourceRef ? RegisterListener(listenerRef)
      listener ← (listenerRef ? GiveMeListener).mapTo[OCommandResultListener]
    } yield {
      //TODO: SmartOSQLNonBlockingQuery starts a new future, so we kinda have redundancy (and we need to activate db twice...)
      db.activateOnCurrentThread()
      val oQuery = SmartOSQLNonBlockingQuery[A](query, limit, fetchPlan, arguments, listener)
      val future: Future[Unit] = db.command(oQuery).execute(params: _*)
      future.onFailure(handleErrorAtSource)
      future.onSuccess { case _ ⇒ sourceRef ! Complete }
    }).onFailure(handleErrorAtSource)

    ActorPublisher[A](sourceRef)
  }
}
