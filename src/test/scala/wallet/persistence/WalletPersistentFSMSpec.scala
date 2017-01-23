package wallet.persistence

import java.io.File
import java.util.UUID

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.Cluster
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.persistence.fsm.PersistentFSM.{CurrentState, SubscribeTransitionCallBack, Transition}
import akka.testkit.{TestKit, TestProbe}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import wallet.persistence.WalletEventStreamMessages.WalletTransactionCompletedEvent
import wallet.services.{CreditScore, CreditVerificationT, WalletCreationNotifierT}
import wallet.transaction.WalletTransaction.WalletTransactionId
import wallet.transaction.{InsufficientWalletFunds, InvalidWalletTransaction}

import scala.concurrent.duration._
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

/**
  * Tests consumer of AccountActor behavior
  */
class WalletPersistentFSMSpec
  extends TestKit(ActorSystem("test-system"))
    with WordSpecLike
    with Matchers
    with BeforeAndAfterAll {

  import WalletPersistentFSM._

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

  class TestAccountVerification extends CreditVerificationT {

    override def getCreditCheck(name: String, lastName: String): Future[Try[CreditScore]] = {
      if (lastName.contains("lowScore")) Future.successful(Success(CreditScore(10)))
      else if(lastName.contains("errorScore")) Future.successful(Failure(new Exception("You broke buddy")))
      else if(lastName.contains("failedScore")) Future.failed(new Exception("Service is down"))
      else Future.successful(Success(CreditScore(200)))
    }
  }

  class WalletCreationNotifier(notify: ActorRef) extends WalletCreationNotifierT {

    override def notifySuccess(walletNumber: String): Unit =
      notify ! (walletNumber -> Success(()))

    override def notifyFailure(walletNumber: String, reason: String): Unit =
      notify ! walletNumber -> Failure(new Exception(reason))
  }

  def generateTransactionId: WalletTransactionId = UUID.randomUUID().toString

  val walletCreationNotifier = TestProbe()

  val accountRegion: ActorRef = ClusterSharding(system).start(
    typeName = shardName,
    entityProps =
      WalletPersistentFSM.props(
        new TestAccountVerification,
        new ClusterWalletRefProvider,
        new WalletCreationNotifier(walletCreationNotifier.ref)),
    settings = ClusterShardingSettings(system),
    extractEntityId = extractEntityId,
    extractShardId = extractShardId)

  "Wallet" should {

    "throw an exception if tries to access details of empty wallet" in {
      assertThrows[IllegalAccessException] {
        EmptyWallet.accNumber
      }
      assertThrows[IllegalAccessException] {
        EmptyWallet.name
      }
      assertThrows[IllegalAccessException] {
        EmptyWallet.lastName
      }
    }

    "throw an exception if tries to create an increase balance message with negative amount" in {

      assertThrows[IllegalArgumentException] {
        IncreaseWalletBalance("", -1, generateTransactionId)
      }
    }
    "throw an exception if tries to create an decrease balance message with negative amount" in {

      assertThrows[IllegalArgumentException] {
        DecreaseWalletBalance("", -1, generateTransactionId)
      }
    }
    "throw an exception if tries to create an increase balance event with negative amount" in {

      assertThrows[IllegalArgumentException] {
        WalletBalanceIncreased(generateTransactionId, -1)
      }
    }
    "throw an exception if tries to create an decrease balance event with negative amount" in {

      assertThrows[IllegalArgumentException] {
        WalletBalanceDecreased(generateTransactionId, -1)
      }
    }
    "throw an exception if tries to create a transfer money debited event with negative amount" in {

      assertThrows[IllegalArgumentException] {
        WalletTransferMoneyDebited("", generateTransactionId, -1)
      }
    }
    "throw an exception if tries to create a transfer money credited event with negative amount" in {

      assertThrows[IllegalArgumentException] {
        WalletTransferMoneyCredited(generateTransactionId, -1)
      }
    }

    "have unique name for each wallet state" in {

      val stateNames = Set(WalletPendingCreationState.identifier, WalletInactiveState.identifier, WalletActiveState.identifier)
      stateNames.size === 3
    }
  }

  "User with high credit score" should {

    val accNumber = "1234"
    val name = "Big"
    val lastName = "Lebowski"

    "fail to increase balance if wallet doesn't exist yet" in {

      val test = TestProbe()
      accountRegion.tell(IncreaseWalletBalance(accNumber, 1, generateTransactionId), test.ref)
      test.expectMsgType[InvalidWalletTransaction]
    }

    "successfully acknowledge a `create account` request" in {

      val test = TestProbe()
      accountRegion.tell(CreateWallet(accNumber, name, lastName), test.ref)
      test.expectMsgPF() {
        case WalletCreationRequestAcknowledged(wallet) if wallet.accNumber == accNumber =>
      }
    }

    "successfully increase balance" in {

      val increase = 10
      val transactionId = generateTransactionId
      val test = TestProbe()
      val expected = WalletBalanceIncreased(transactionId, increase)
      accountRegion.tell(IncreaseWalletBalance(accNumber, increase, transactionId), test.ref)
      test.expectMsg(expected)
    }

    "successfully decrease balance" in {

      val decrease = 8
      val transactionId = generateTransactionId
      val test = TestProbe()
      val expected = WalletBalanceDecreased(transactionId, decrease)
      accountRegion.tell(DecreaseWalletBalance(accNumber, decrease, transactionId), test.ref)
      test.expectMsg(expected)
    }

    "fail to decrease balance due to insufficient funds" in {

      val decrease = 3
      val test = TestProbe()
      accountRegion.tell(DecreaseWalletBalance(accNumber, decrease, generateTransactionId), test.ref)
      test.expectMsg(InsufficientWalletFunds)
    }

    "successfully get current balance" in {

      val test = TestProbe()
      accountRegion.tell(GetCurrentWalletBalance(accNumber), test.ref)
      test.expectMsg(2.0)
    }

    "fail to create account if account already exists" in {

      val test = TestProbe()
      accountRegion.tell(CreateWallet(accNumber, name, lastName), test.ref)
      test.expectMsgType[InvalidWalletTransaction]
    }

    // Default configuration is to passivate after 5 second
    /*"publish AccountPassivatedEvent when timeout is reached" in {

      val test = TestProbe()
      system.eventStream.subscribe(
        test.ref,
        classOf[AccountPassivatedEvent])
      test.expectMsgType[AccountPassivatedEvent](7.seconds)
    }

    "successfully recover after being passivated" in {

      val test = TestProbe()
      accountRegion.tell(GetCurrentBalance(walletDetails.accNumber), test.ref)
      test.expectMsgPF() { case Success(balance) =>
        balance === 2
      }
    }*/
  }

  /*"AccountActor with a lot of activity " should {

    "publish AccountTakeSnapshotEvent when interval elapses" in {

      val accNumber = "4321"
      accountRegion ! CreateWallet(accNumber, "Last", "Samurai-highScore")
      val test = TestProbe()
      system.eventStream.subscribe(
        test.ref,
        classOf[AccountTakeSnapshotEvent])
      (1 to 100).foreach(accountRegion ! IncreaseBalance(accNumber, _, generateTransactionId))
      test.expectMsgType[AccountTakeSnapshotEvent](6.seconds)
    }
  }*/

  "AccountActor with low credit score" should {

    val accNumber = "5678"
    val name = "Harry"
    val lastName = "Potter-lowScore"

    "fail to increase balance if account doesn't exist yet" in {

      val test = TestProbe()
      accountRegion.tell(IncreaseWalletBalance(accNumber, 1, generateTransactionId), test.ref)
      test.expectMsgType[InvalidWalletTransaction]
    }

    "successfully acknowledge a `create account` request" in {

      val test = TestProbe()
      accountRegion.tell(CreateWallet(accNumber, name, lastName), test.ref)
      test.expectMsgPF() {
        case WalletCreationRequestAcknowledged(wallet) if wallet.accNumber === accNumber =>
      }
    }

    "fail to increase balance due to inactive account" in {

      val increase = 10
      val test = TestProbe()
      accountRegion.tell(IncreaseWalletBalance(accNumber, increase, generateTransactionId), test.ref)
      test.expectMsgType[InvalidWalletTransaction]
    }

    "fail to decrease balance due to inactive account" in {

      val decrease = 8
      val test = TestProbe()
      accountRegion.tell(DecreaseWalletBalance(accNumber, decrease, generateTransactionId), test.ref)
      test.expectMsgType[InvalidWalletTransaction]
    }


    "fail to get current balance due to inactive account" in {

      val test = TestProbe()
      accountRegion.tell(GetCurrentWalletBalance(accNumber), test.ref)
      test.expectMsgType[InvalidWalletTransaction]
    }

    "fail to create account if account already exists" in {

      val test = TestProbe()
      accountRegion.tell(CreateWallet(accNumber, name, lastName), test.ref)
      test.expectMsgType[InvalidWalletTransaction]
    }
  }

  "AccountActor with error credit score" should {

    val accNumber = "7390"
    val name = "Bloody"
    val lastName = "Mary-errorScore"

    "fail to increase balance if account doesn't exist yet" in {

      val test = TestProbe()
      accountRegion.tell(IncreaseWalletBalance(accNumber, 1, generateTransactionId), test.ref)
      test.expectMsgType[InvalidWalletTransaction]
    }

    "successfully acknowledge a `create account` request" in {

      val test = TestProbe()
      accountRegion.tell(CreateWallet(accNumber, name, lastName), test.ref)
      test.expectMsgPF() {
        case WalletCreationRequestAcknowledged(wallet) if wallet.accNumber === accNumber =>
      }
    }

    "fail to increase balance due to inactive account" in {

      val increase = 10
      val test = TestProbe()
      accountRegion.tell(IncreaseWalletBalance(accNumber, increase, generateTransactionId), test.ref)
      test.expectMsgType[InvalidWalletTransaction]
    }

    "fail to decrease balance due to inactive account" in {

      val decrease = 8
      val test = TestProbe()
      accountRegion.tell(DecreaseWalletBalance(accNumber, decrease, generateTransactionId), test.ref)
      test.expectMsgType[InvalidWalletTransaction]
    }


    "fail to get current balance due ot inactive account" in {

      val test = TestProbe()
      accountRegion.tell(GetCurrentWalletBalance(accNumber), test.ref)
      test.expectMsgType[InvalidWalletTransaction]
    }

    "fail to create account if account already exists" in {

      val test = TestProbe()
      accountRegion.tell(CreateWallet(accNumber, name, lastName), test.ref)
      test.expectMsgType[InvalidWalletTransaction]
    }
  }

  "AccountActor that got fail while calling credit score check" should {

    val accNumber = "5279"
    val name = "Donald"
    val lastName = "Duck-failedScore"

    "successfully acknowledge a `create account` request and transition to inactive state" in {

      val test = TestProbe()

      val fsmRef = system.actorOf(WalletPersistentFSM.props(
        new TestAccountVerification,
        new ClusterWalletRefProvider,
        new WalletCreationNotifier(walletCreationNotifier.ref)))

      fsmRef ! SubscribeTransitionCallBack(test.ref)
      fsmRef.tell(CreateWallet(accNumber, name, lastName), test.ref)
      test.expectMsg(CurrentState(fsmRef, WalletPendingCreationState, None))
      test.expectMsgPF() {
        case WalletCreationRequestAcknowledged(wallet) if wallet.accNumber === accNumber =>
      }
      test.expectMsg(Transition(fsmRef, WalletPendingCreationState, WalletInactiveState, None))
    }

  }

  "AccountActor with good credit score" should {

    val walletA = BasicWallet("2193", "Jerry", "Cook")
    val walletB = BasicWallet("2194", "Thomas", "Cay")
    val transactionId = generateTransactionId
    val balanceA = 12.0
    val moveAmount = 5.5

    "successfully acknowledge 2 `create account` requests" in {

      val test = TestProbe()
      accountRegion.tell(CreateWallet(walletA.accNumber, walletA.name, walletA.lastName), test.ref)
      accountRegion.tell(CreateWallet(walletB.accNumber, walletB.name, walletB.lastName), test.ref)
      test.receiveN(2).collect {
        case msg: WalletCreationRequestAcknowledged =>
          msg.wallet.accNumber === walletA.accNumber ||
            msg.wallet.accNumber === walletB.accNumber
      }.size == 2
    }

    "successfully increase balance of one the accounts" in {

      val test = TestProbe()
      val expected = WalletBalanceIncreased(transactionId, balanceA)
      accountRegion.tell(IncreaseWalletBalance(walletA.accNumber, balanceA, transactionId), test.ref)
      test.expectMsg(expected)
    }

    "successfully move balance from one account to another" in {

      val test = TestProbe()
      val expectedDebited = WalletTransferMoneyDebited(walletB.accNumber, transactionId, moveAmount)
      accountRegion.tell(
        TransferBalanceBetweenWallets(walletA.accNumber, moveAmount, walletB.accNumber, transactionId),
        test.ref)
      test.expectMsg(expectedDebited)
      system.eventStream.subscribe(
        test.ref,
        classOf[WalletTransactionCompletedEvent])
      val expectedCompleted = WalletTransactionCompletedEvent(walletA.accNumber, transactionId)
      test.expectMsg(expectedCompleted)
      accountRegion.tell(GetCurrentWalletBalance(walletA.accNumber), test.ref)
      test.expectMsg(balanceA - moveAmount)
      accountRegion.tell(GetCurrentWalletBalance(walletB.accNumber), test.ref)
      test.expectMsg(moveAmount)
    }

    "fail to move balance if there is not enough funds" in {
      val test = TestProbe()
      accountRegion.tell(
        TransferBalanceBetweenWallets(walletA.accNumber, 10000, walletB.accNumber, transactionId), test.ref)
      test.expectMsg(InsufficientWalletFunds)
      accountRegion.tell(GetCurrentWalletBalance(walletA.accNumber), test.ref)
      test.expectMsg(balanceA - moveAmount)
      accountRegion.tell(GetCurrentWalletBalance(walletB.accNumber), test.ref)
      test.expectMsg(moveAmount)
    }
  }
}
