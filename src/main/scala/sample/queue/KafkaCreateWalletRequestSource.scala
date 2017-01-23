package sample.queue

import akka.Done
import akka.actor.ActorSystem
import akka.kafka.ConsumerMessage.CommittableOffset
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerMessage, ConsumerSettings, Subscriptions}
import akka.stream.scaladsl.Source
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.{ByteArrayDeserializer, StringDeserializer}

import scala.concurrent.{ExecutionContext, Future}

/**
  * Source for CreateAccount requests
  */
sealed trait CreateAccountReqConsumeEvents
final case class CreateAccountReqCommittingEvent(id: String) extends CreateAccountReqConsumeEvents {
  val TRYING_TO_COMMIT_MESSAGE = "Trying to Commit ..."
}
final case class CreateAccountReqEventCommitted(id: String) extends CreateAccountReqConsumeEvents {
  val COMMITTED_MESSAGE = "Committed !!!!"
}

object CreateAccountReqConsumeMessage {

  final case class CreateAccountReqCommittable(
    private val committableOffset: ConsumerMessage.CommittableOffset) {

    private[this] val committableOffsetToString = "key: " + committableOffset.partitionOffset.key +
      ", offset: " + committableOffset.partitionOffset.offset

    //TODO: what if fails to commit ??
    def commit()(
      implicit ec: ExecutionContext, system: ActorSystem): Future[Done] = {
      system.eventStream.publish(CreateAccountReqCommittingEvent(committableOffsetToString))
      val f = committableOffset.commitScaladsl()
      f.foreach {
        _ => system.eventStream.publish(CreateAccountReqEventCommitted(committableOffsetToString))
      }
      f
    }
  }

  final case class CreateAccountReqCommittableMessage(
    private val committableMessage:
      ConsumerMessage.CommittableMessage[Array[Byte],
        String]) {

    def value: String  = committableMessage.record.value()

    def committableOffset() = CreateAccountReqConsumeMessage
      .CreateAccountReqCommittable(committableMessage.committableOffset)
  }

}

trait CreateWalletRequestSourceT {

  def groupId: String
  def bootStrapServers: String
  def topic: String
  def getCreateAccountReqSource()(implicit system: ActorSystem): Source[
    CreateAccountReqConsumeMessage.CreateAccountReqCommittableMessage,
    Consumer.Control]
}

//TODO: provide a way to close gracefully
trait KafkaCreateWalletRequestSource extends CreateWalletRequestSourceT {

  override val groupId = "groupId"
  override val bootStrapServers = "localhost:9092"
  override val topic = "CreateAccountRequest"

  override def getCreateAccountReqSource()(implicit system: ActorSystem):
  Source[/*ConsumerMessage.CommittableMessage[Array[Byte], String]*/
  CreateAccountReqConsumeMessage.CreateAccountReqCommittableMessage,
    Consumer.Control] = {

    val consumerSettings = ConsumerSettings(system,
      new ByteArrayDeserializer, new StringDeserializer)
      .withBootstrapServers(bootStrapServers)
      .withGroupId(groupId)
      .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
      // .withProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false") disabled by-default still better have it

    Consumer.committableSource(consumerSettings, Subscriptions.topics(topic))
      .map { msg =>
        CreateAccountReqConsumeMessage.CreateAccountReqCommittableMessage(msg)
      }
  }

}
