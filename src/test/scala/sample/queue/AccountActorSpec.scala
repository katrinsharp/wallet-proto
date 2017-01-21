package sample.queue

import java.io.File
import java.util.UUID

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.cluster.Cluster
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings, ShardRegion}
import akka.testkit.{TestKit, TestProbe}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import sample.persistence.AccountActor

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}
import scala.concurrent.duration._

/**
  * Tests consumer of AccountActor behavior
  */
class AccountActorSpec
  extends TestKit(ActorSystem("test-system"))
    with WordSpecLike
    with Matchers
    with BeforeAndAfterAll {

  import AccountActor._

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    val cluster = Cluster(system)
    cluster.join(cluster.selfAddress)
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    TestKit.shutdownActorSystem(system)
    val journal = system.settings.config.getString("akka.persistence.journal.leveldb.dir")
    val snapshot = system.settings.config.getString("akka.persistence.snapshot-store.local.dir")
    deleteRecursively(new File(journal))
    deleteRecursively(new File(snapshot))
  }

  def deleteRecursively(file: File): Unit = {
      if (file.isDirectory)
        file.listFiles.foreach(deleteRecursively)
      if (file.exists && !file.delete)
        throw new Exception(s"Unable to delete ${file.getAbsolutePath}")
  }

  class TestAccountVerification extends AccountVerificationT {

    override def getCreditCheck(name: String, lastName: String): Future[Try[CreditScore]] = {
      if (lastName.contains("lowScore")) Future.successful(Success(CreditScore(10)))
      else if(lastName.contains("errorScore")) Future.successful(Failure(new Exception("You broke buddy")))
      else Future.successful(Success(CreditScore(200)))
    }
  }

  def generateTransactionId: TransactionId = UUID.randomUUID().toString

  val accountRegion: ActorRef = ClusterSharding(system).start(
    typeName = shardName,
    entityProps =
      Props(classOf[AccountActor], new TestAccountVerification, new ClusterAccountActorProvider),
    settings = ClusterShardingSettings(system),
    extractEntityId = extractEntityId,
    extractShardId = extractShardId)

  "AccountActor with high credit score" should {

    val accNumber = "1234"
    val accountDetails = BasicAccountDetails(accNumber, "Big", "Lebowski")

    "fail to increase balance if account doesn't exist yet" in {

      val test = TestProbe()
      accountRegion.tell(IncreaseBalance(accNumber, 1, generateTransactionId), test.ref)
      test.expectMsgPF() { case Failure(err: AccountError) =>
        err.getMessage.contains("invalid command") === true
      }
    }

    "successfully acknowledge a `create account` request" in {

      val test = TestProbe()
      accountRegion.tell(CreateAccount(accountDetails), test.ref)
      test.expectMsgPF() { case msg: AccountCreationRequestAcknowledged =>
        msg.accountDetails.accNumber === accNumber
      }
    }

    "successfully increase balance" in {

      val increase = 10
      val test = TestProbe()
      accountRegion.tell(IncreaseBalance(accNumber, increase, generateTransactionId), test.ref)
      test.expectMsgPF() { case msg: BalanceIncreased =>
        msg.amount === increase
      }
    }

    "successfully decrease balance" in {

      val decrease = 8
      val test = TestProbe()
      accountRegion.tell(DecreaseBalance(accNumber, decrease, generateTransactionId), test.ref)
      test.expectMsgPF() { case msg: BalanceDecreased =>
        msg.amount === decrease
      }
    }

    "fail to decrease balance due to insufficient funds" in {

      val decrease = 3
      val test = TestProbe()
      accountRegion.tell(DecreaseBalance(accNumber, decrease, generateTransactionId), test.ref)
      test.expectMsgPF() { case Failure(err: AccountError) =>
        err.getMessage.contains("insufficient funds") === true
      }
    }

    "successfully get current balance" in {

      val test = TestProbe()
      accountRegion.tell(GetCurrentBalance(accNumber), test.ref)
      test.expectMsgPF() { case balance =>
        balance === 2
      }
    }

    "fail to create account if account already exists" in {

      val test = TestProbe()
      accountRegion.tell(CreateAccount(accountDetails), test.ref)
      test.expectMsgPF() { case Failure(err: AccountError) =>
        err.getMessage.contains("invalid command") === true
      }
    }

    "fail to increase balance if amount is negative" in {

      val increase = -1
      val test = TestProbe()
      accountRegion.tell(IncreaseBalance(accNumber, increase, generateTransactionId), test.ref)
      test.expectMsgPF() { case Failure(err: AccountError) =>
        err.getMessage.contains("negative amount") === true
      }
    }

    "fail to decrease balance if amount is negative" in {

      val increase = -1
      val test = TestProbe()
      accountRegion.tell(DecreaseBalance(accNumber, increase, generateTransactionId), test.ref)
      test.expectMsgPF() { case Failure(err: AccountError) =>
        err.getMessage.contains("negative amount") === true
      }
    }

    // Default configuration is to passivate after 5 second
    "publish AccountPassivatedEvent when timeout is reached" in {

      val test = TestProbe()
      system.eventStream.subscribe(
        test.ref,
        classOf[AccountPassivatedEvent])
      test.expectMsgType[AccountPassivatedEvent](7.seconds)
    }

    "successfully recover after being passivated" in {

      val test = TestProbe()
      accountRegion.tell(GetCurrentBalance(accNumber), test.ref)
      test.expectMsgPF() { case Success(balance) =>
        balance === 2
      }
    }
  }

  "AccountActor with a lot of activity " should {

    "publish AccountTakeSnapshotEvent when interval elapses" in {

      val accNumber = "4321"
      val accountDetails = BasicAccountDetails(accNumber, "Last", "Samurai-highScore")
      accountRegion ! CreateAccount(accountDetails)
      val test = TestProbe()
      system.eventStream.subscribe(
        test.ref,
        classOf[AccountTakeSnapshotEvent])
      (1 to 100).foreach(accountRegion ! IncreaseBalance(accNumber, _, generateTransactionId))
      test.expectMsgType[AccountTakeSnapshotEvent](6.seconds)
    }
  }

  "AccountActor with low credit score" should {

    val accNumber = "5678"
    val accountDetails = BasicAccountDetails(accNumber, "Harry", "Potter-lowScore")

    "fail to increase balance if account doesn't exist yet" in {

      val test = TestProbe()
      accountRegion.tell(IncreaseBalance(accNumber, 1, generateTransactionId), test.ref)
      test.expectMsgPF() { case Failure(err: AccountError) =>
        err.getMessage.contains("invalid command") === true
      }
    }

    "successfully acknowledge a `create account` request" in {

      val test = TestProbe()
      accountRegion.tell(CreateAccount(accountDetails), test.ref)
      test.expectMsgPF() { case msg: AccountCreationRequestAcknowledged =>
        msg.accountDetails.accNumber === accNumber
      }
    }

    "fail to increase balance due to inactive account" in {

      val increase = 10
      val test = TestProbe()
      accountRegion.tell(IncreaseBalance(accNumber, increase, generateTransactionId), test.ref)
      test.expectMsgPF() { case Failure(err: AccountError) =>
        err.getMessage.contains("low score") === true
      }
    }

    "fail to decrease balance due to inactive account" in {

      val decrease = 8
      val test = TestProbe()
      accountRegion.tell(DecreaseBalance(accNumber, decrease, generateTransactionId), test.ref)
      test.expectMsgPF() { case Failure(err: AccountError) =>
        err.getMessage.contains("low score") === true
      }
    }


    "fail to get current balance due to inactive account" in {

      val test = TestProbe()
      accountRegion.tell(GetCurrentBalance(accNumber), test.ref)
      test.expectMsgPF() { case Failure(err: AccountError) =>
        err.getMessage.contains("low score") === true
      }
    }

    "fail to create account if account already exists" in {

      val test = TestProbe()
      accountRegion.tell(CreateAccount(accountDetails), test.ref)
      test.expectMsgPF() { case Failure(err: AccountError) =>
        err.getMessage.contains("invalid command") === true
      }
    }
  }

  "AccountActor with error credit score" should {

    val accNumber = "7390"
    val accountDetails = BasicAccountDetails(accNumber, "Bloody", "Mary-errorScore")

    "fail to increase balance if account doesn't exist yet" in {

      val test = TestProbe()
      accountRegion.tell(IncreaseBalance(accNumber, 1, generateTransactionId), test.ref)
      test.expectMsgPF() { case Failure(err: AccountError) =>
        err.getMessage.contains("invalid command") === true
      }
    }

    "successfully acknowledge a `create account` request" in {

      val test = TestProbe()
      accountRegion.tell(CreateAccount(accountDetails), test.ref)
      test.expectMsgPF() { case msg: AccountCreationRequestAcknowledged =>
        msg.accountDetails.accNumber === accNumber
      }
    }

    "fail to increase balance due to inactive account" in {

      val increase = 10
      val test = TestProbe()
      accountRegion.tell(IncreaseBalance(accNumber, increase, generateTransactionId), test.ref)
      test.expectMsgPF() { case Failure(err: AccountError) =>
        err.getMessage.contains("you broke") === true
      }
    }

    "fail to decrease balance due to inactive account" in {

      val decrease = 8
      val test = TestProbe()
      accountRegion.tell(DecreaseBalance(accNumber, decrease, generateTransactionId), test.ref)
      test.expectMsgPF() { case Failure(err: AccountError) =>
        err.getMessage.contains("you broke") === true
      }
    }


    "fail to get current balance due ot inactive account" in {

      val test = TestProbe()
      accountRegion.tell(GetCurrentBalance(accNumber), test.ref)
      test.expectMsgPF() { case Failure(err: AccountError) =>
        err.getMessage.contains("you broke") === true
      }
    }

    "fail to create account if account already exists" in {

      val test = TestProbe()
      accountRegion.tell(CreateAccount(accountDetails), test.ref)
      test.expectMsgPF() { case Failure(err: AccountError) =>
        err.getMessage.contains("invalid command") === true
      }
    }
  }

  "AccountActor with good credit score" should {

    val accountDetailsA = BasicAccountDetails("2193", "Jerry", "Cook")
    val accountDetailsB = BasicAccountDetails("2194", "Thomas", "Cay")
    val transactionId = generateTransactionId
    val balanceA = 10
    val moveAmount = 5.5

    "successfully acknowledge 2 `create account` requests" in {

      val test = TestProbe()
      accountRegion.tell(CreateAccount(accountDetailsA), test.ref)
      accountRegion.tell(CreateAccount(accountDetailsB), test.ref)
      test.receiveN(2).collect {
        case msg: AccountCreationRequestAcknowledged =>
          msg.accountDetails.accNumber === accountDetailsA.accNumber ||
            msg.accountDetails.accNumber === accountDetailsB.accNumber
      }.size == 2
    }

    "successfully increase balance of one the accounts" in {

      val test = TestProbe()
      accountRegion.tell(IncreaseBalance(accountDetailsA.accNumber, balanceA, transactionId), test.ref)
      test.expectMsgPF() { case msg: BalanceIncreased =>
        msg.amount === balanceA
      }
    }

    "successfully move balance from one account to another" in {

      val test = TestProbe()
      accountRegion.tell(
        MoveBalance(accountDetailsA.accNumber, moveAmount, accountDetailsB.accNumber, transactionId), test.ref)
      test.expectMsgPF() { case MoveMoneyDebited(thisTransactionId, amount, targetAccNumber) =>
        amount === moveAmount
        targetAccNumber === accountDetailsB.accNumber
        thisTransactionId === transactionId
      }
      system.eventStream.subscribe(
        test.ref,
        classOf[AccountTransactionCompletedEvent])
      test.expectMsgPF() { case AccountTransactionCompletedEvent(accNumber, completedTransactionId) =>
        accNumber === accountDetailsA.accNumber
        completedTransactionId === transactionId
      }
      accountRegion.tell(GetCurrentBalance(accountDetailsA.accNumber), test.ref)
      test.expectMsgPF() { case Success(balance) =>
        balance === (balanceA - moveAmount)
      }
      accountRegion.tell(GetCurrentBalance(accountDetailsB.accNumber), test.ref)
      test.expectMsgPF() { case Success(balance) =>
        balance === moveAmount
      }
    }

    "fail to move balance if there is not enough funds" in {
      val test = TestProbe()
      accountRegion.tell(
        MoveBalance(accountDetailsA.accNumber, 10000, accountDetailsB.accNumber, transactionId), test.ref)
      test.expectMsgPF() { case Failure(err: AccountError) =>
        err.getMessage.contains("insufficient funds") === true
      }
      accountRegion.tell(GetCurrentBalance(accountDetailsA.accNumber), test.ref)
      test.expectMsgPF() { case Success(balance) =>
        balance === (balanceA - moveAmount)
      }
      accountRegion.tell(GetCurrentBalance(accountDetailsB.accNumber), test.ref)
      test.expectMsgPF() { case Success(balance) =>
        balance === moveAmount
      }
    }
  }
}
