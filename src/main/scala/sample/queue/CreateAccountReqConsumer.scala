package sample.queue

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.pattern.{AskTimeoutException, ask}
import akka.util.Timeout

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
  * Continuously consumes CreateAccount requests from queue
  */
object CreateAccountReqConsumer {

  val ERROR_SENDING_TO_ACCOUNT_ACTOR_MESSAGE = "Error sending to account actor"
  val TIMEOUT_SENDING_TO_ACCOUNT_ACTOR_MESSAGE = "Timeout sending to account actor"
}

class CreateAccountReqConsumer()(implicit system: ActorSystem) {

  this: CreateAccountReqSourceT with AccountActorProviderT =>

  import CreateAccountReqConsumer._

  private implicit val timeout: Timeout = FiniteDuration(
    system.settings.config.getDuration(
      "create-account-req-consumer.timeout",
      MILLISECONDS),
    MILLISECONDS)

  private implicit val context = system.dispatcher // TODO: move to its own context

  def start(): Unit = {

    implicit val materializer = ActorMaterializer()
    val source = getCreateAccountReqSource()

    //TODO: should we send more than 1 at a time before waiting ?
    source
      .mapAsync(1) { msg =>

        (getAccountActor ? msg.value).map {
          case Success(()) => Success(msg.committableOffset())
          case _ => // TODO: recognize the error
            system.log.error(ERROR_SENDING_TO_ACCOUNT_ACTOR_MESSAGE)
            Failure(new Throwable(ERROR_SENDING_TO_ACCOUNT_ACTOR_MESSAGE))
        }.recover {
          case ex: AskTimeoutException =>
            system.log.error(TIMEOUT_SENDING_TO_ACCOUNT_ACTOR_MESSAGE)
            Failure(ex)
        }
      }
      .mapAsync(1) {
        case Success(committableOffset) => committableOffset.commit()
        case Failure(ex) => Future.failed(ex) // TODO: some kind of restart
      }
      .runWith(Sink.ignore)
  }
}
