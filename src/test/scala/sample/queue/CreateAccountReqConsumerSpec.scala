package sample.queue

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{EventFilter, TestActor, TestKit, TestProbe}
import net.manub.embeddedkafka.EmbeddedKafka
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.util.{Failure, Success}

/**
  * Tests consumer of CreateAccount requests
  */
class CreateAccountReqConsumerSpec
  extends TestKit(ActorSystem("test-system"))
    with WordSpecLike
    with EmbeddedKafka
    with Matchers
    with BeforeAndAfterAll {

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    EmbeddedKafka.start()
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    EmbeddedKafka.stop()
    TestKit.shutdownActorSystem(system)
  }

  trait TestKafkaCreateAccountReqKafkaSource extends KafkaCreateAccountReqSource {

    override val bootStrapServers = "localhost:6001"
  }

  trait TestAccountActorProvider extends AccountActorProviderT {

    val testProbe = TestProbe()
    override def getAccountActor()(implicit system: ActorSystem) = testProbe.ref
  }

  val testKafkaConsumer = new CreateAccountReqConsumer()
    with TestKafkaCreateAccountReqKafkaSource with TestAccountActorProvider

    "CreateAccountReqConsumer" should {

      "successfully consume a message and commit if CreateAccountActor replies success" in {

        val message = "Hello world 1!"
        publishStringMessageToKafka(testKafkaConsumer.topic, message)
        testKafkaConsumer.start()
        testKafkaConsumer.testProbe.expectMsg(message)
        val test = TestProbe()
        system.eventStream.subscribe(
          test.ref,
          classOf[CreateAccountReqConsumeEvents])
        testKafkaConsumer.testProbe.reply(Success(()))
        test.expectMsgType[CreateAccountReqCommittingEvent]
        test.expectMsgType[CreateAccountReqEventCommitted]
      }

      "successfully consume a message and NOT commit if CreateAccountActor replies error" in {

        val message = "Hello world 2!"
        publishStringMessageToKafka(testKafkaConsumer.topic, message)
        testKafkaConsumer.start()
        testKafkaConsumer.testProbe.expectMsg(message)
        val test = TestProbe()
        system.eventStream.subscribe(
          test.ref,
          classOf[CreateAccountReqConsumeEvents])
        testKafkaConsumer.testProbe.reply(Failure(new Exception("Error")))
        test.expectNoMsg()
      }

      "successfully consume a message and NOT commit if CreateAccountActor times out" in {

        val message = "Hello world 3!"
        publishStringMessageToKafka(testKafkaConsumer.topic, message)
        testKafkaConsumer.start()
        // testKafkaConsumer.testProbe.expectMsg(message)
        val test = TestProbe()
        system.eventStream.subscribe(
          test.ref,
          classOf[CreateAccountReqConsumeEvents])
        test.expectNoMsg()
      }
    }
}
