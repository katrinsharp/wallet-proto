package wallet.persistence

import akka.actor.{NotInfluenceReceiveTimeout, Props, ReceiveTimeout, Status}
import akka.cluster.sharding.ShardRegion
import akka.cluster.sharding.ShardRegion.Passivate
import akka.persistence.fsm.PersistentFSM

import scala.reflect._
import scala.util.{Failure, Success}
import akka.pattern.pipe
import akka.persistence.{SaveSnapshotFailure, SaveSnapshotSuccess}
import wallet.persistence.WalletEventStreamMessages._
import wallet.services.{CreditScore, CreditVerificationT, WalletCreationNotifierT}
import wallet.transaction.{InsufficientWalletFunds, InvalidWalletTransaction, WalletTransaction}

import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}

/**
  * Wallet representation that persists its state and can be queried
  * TODO: handle
  * 1. https://github.com/akka/akka/issues/15171,
  *    https://groups.google.com/forum/#!topic/akka-user/bxiPjRsItqc
  */
object WalletPersistentFSM {

  val shardName = "Wallet"

  def props(
    creditVerifier: CreditVerificationT,
    walletRefProvider: WalletRefProviderT,
    walletCreationNotifier: WalletCreationNotifierT) =
    Props(new WalletPersistentFSM(
      creditVerifier,
      walletRefProvider,
      walletCreationNotifier))

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case cmd: WalletCommand => (cmd.accNumber, cmd)
  }

  val extractShardId: ShardRegion.ExtractShardId = {
    case cmd: WalletCommand => cmd.id
  }

  case object PassivateWallet

  // Scheduling a snapshot won't delay the timeout
  //TODO: Does it delay StateTimeout? If it does, Empty wallet should be handled differently
  final case object WalletSnapshot extends NotInfluenceReceiveTimeout

}

class WalletPersistentFSM(
  accVerifier: CreditVerificationT,
  walletRefProvider: WalletRefProviderT,
  walletCreationNotifier: WalletCreationNotifierT)
  (implicit val domainEventClassTag: ClassTag[WalletEvent])
  extends PersistentFSM[WalletState, Wallet, WalletEvent] {

  import WalletPersistentFSM._

  implicit private val executionContext = context.dispatcher
  implicit private val system = context.system

  val passivateTimeout = FiniteDuration(
    context.system.settings.config.getDuration(
      "wallet.passivateTimeout",
      MILLISECONDS),
    MILLISECONDS)

  val snapshotDelay = FiniteDuration(
    context.system.settings.config.getDuration(
      "wallet.snapshotDelay",
      MILLISECONDS),
    MILLISECONDS)

  val snapshotInterval = FiniteDuration(
    context.system.settings.config.getDuration(
      "wallet.snapshotInterval",
      MILLISECONDS),
    MILLISECONDS)

  context.setReceiveTimeout(passivateTimeout)
  val snapShotSchedule = context.system.scheduler.schedule(snapshotDelay, snapshotInterval, self, WalletSnapshot)

  // TODO: Empty wallet should be discarded rather passivate.
  // TODO: Not sure it can be actually created if going shardRegion route only
  private def passivate(w: Wallet) = {

    val accNumber = w match {
      case EmptyWallet => ""
      case _:Wallet => w.accNumber
    }
    log.debug("PassivateWallet: " + " `" + accNumber + "`")
    context.system.eventStream.publish(WalletPassivatedEvent(accNumber))
    stop
  }

  override def onRecoveryCompleted(): Unit = {

    context.system.eventStream.publish(WalletRecoveryCompletedEvent(""))
  }

  override def postStop(): Unit = {
    snapShotSchedule.cancel()
    super.postStop()
  }


  //TODO: we might need to change stay() to goto(sameState) to prevent Timeout

  override def applyEvent(domainEvent: WalletEvent, currentWallet: Wallet): Wallet = {
    (domainEvent, currentWallet) match {
      case (ev: WalletCreationRequestAcknowledged, EmptyWallet) => ev.wallet
      case (ev: WalletCreated, _: BasicWallet) => ev.wallet
      case (ev: WalletBalanceIncreased, activeWallet: ActiveWallet) =>
        activeWallet.copy(balance = activeWallet.balance + ev.amount)
      case (ev: WalletBalanceDecreased, currentWallet: ActiveWallet) =>
        currentWallet.copy(balance = currentWallet.balance - ev.amount)
      case (ev: WalletTransferMoneyDebited, activeWallet: ActiveWallet) =>
        activeWallet.addTransaction(WalletTransaction(
          id = ev.transactionId,
          amount = ev.amount,
          targetAccNumber = ev.targetAccNumber,
          numberOfTries = 1))
        walletRefProvider.getWalletRef() ! CreditWalletBalance(
          ev.targetAccNumber, ev.amount, ev.transactionId)
        activeWallet.copy(balance = activeWallet.balance - ev.amount)
      // It could become Inactive meanwhile so FullWallet instead of ActiveWallet
      case (ev: WalletTransferMoneyCredited, activeWallet: FullWallet) =>
        //TODO: report if transaction didn't exist
        activeWallet.removeTransaction(ev.transactionId)
        context.system.eventStream.publish(
          WalletTransactionCompletedEvent(activeWallet.accNumber, ev.transactionId))
        activeWallet
      // $COVERAGE-OFF$ //It should never happen
      case (ev: WalletEvent, currentWallet: Wallet) =>
        throw new IllegalStateException(s"Can't recognize state: $ev, $currentWallet")
      // $COVERAGE-ON$
    }
  }

  override def persistenceId: String = shardName + "-" + self.path.name

  startWith(WalletPendingCreationState, EmptyWallet)

  when(WalletPendingCreationState, passivateTimeout) {
    case Event(CreateWallet(accNumber, name, lastName), EmptyWallet) =>
      accVerifier.getCreditCheck(name = name, lastName = lastName)
        .recover{case ex => Failure(ex)}.pipeTo(self)
      val ev = WalletCreationRequestAcknowledged(BasicWallet(accNumber, name, lastName))
      stay.applying(ev).replying(ev)
    case Event(Success(score: CreditScore), currentWallet: BasicWallet) if score.score > 100 =>
        walletCreationNotifier.notifySuccess(currentWallet.accNumber)
        goto(WalletActiveState)
          .applying(WalletCreated(ActiveWallet(currentWallet, score)))
    case Event(Success(score: CreditScore), currentWallet: BasicWallet) =>
      val reason = s"Score is too low: ${score.score}"
      walletCreationNotifier.notifyFailure(currentWallet.accNumber, reason)
      goto(WalletInactiveState)
        .applying(WalletCreated(InactiveWallet(currentWallet, score, reason)))
    case Event(Failure(ex), currentWallet: BasicWallet) =>
      goto(WalletInactiveState)
        .applying(WalletCreated(InactiveWallet(currentWallet, CreditScore.empty, ex.getMessage)))
    //case Event(Status.Failure(ex), currentWallet: Wallet)  =>
    //  goto(WalletInactiveState)
    //    .applying(WalletCreated(InactiveWallet(currentWallet, CreditScore.empty, ex.getMessage)))
    case Event(StateTimeout, w) =>
      //TODO: ReceiveTimeout is received for empty wallet, but Passivate message gets lost.
      // Doing the alternative route for now, find out why later.
      passivate(w)
  }

  when(WalletActiveState) {
    case Event(IncreaseWalletBalance(_, amount, transactionId), _: ActiveWallet) =>
      val ev = WalletBalanceIncreased(transactionId, amount)
      stay.applying(ev).replying(ev)
    case Event(DecreaseWalletBalance(_, amount, transactionId), currentWallet: ActiveWallet) =>
      if(currentWallet.balance >= amount) {
        val ev = WalletBalanceDecreased(transactionId, amount)
        stay.applying(ev).replying(ev)
      } else stay.replying(InsufficientWalletFunds)
    case Event(TransferBalanceBetweenWallets(_, amount, targetAccNumber, transactionId), currentWallet:ActiveWallet) =>
      if(currentWallet.balance >= amount) {
        val ev = WalletTransferMoneyDebited(targetAccNumber, transactionId, amount)
        stay.applying(ev).replying(ev)
      } else stay.replying(InsufficientWalletFunds)
    case Event(CreditWalletBalance(_, amount, transactionId), _: ActiveWallet) =>
      val ev = WalletBalanceIncreased(transactionId, amount)
      val replyEv = WalletTransferMoneyCredited(transactionId, amount)
      stay.applying(ev).replying(replyEv)
    case Event(ev: WalletTransferMoneyCredited, _) =>
      stay.applying(ev)
    case Event(GetCurrentWalletBalance(_), currentWallet:ActiveWallet) =>
      stay.replying(currentWallet.balance)
  }

  when(WalletInactiveState) {
    case Event(cmd: WalletCommand, _) => stay.replying(InvalidWalletTransaction(cmd))
  }

  whenUnhandled {
    case Event(cmd: WalletCommand, _) =>
      stay.replying(InvalidWalletTransaction(cmd))
    case Event(WalletSnapshot, w: FullWallet) =>
      log.debug(s"WalletSnapshot: `$w`")
      context.system.eventStream.publish(WalletSnapshotTakenEvent(w.accNumber))
      saveStateSnapshot()
      stay
    case Event(SaveSnapshotSuccess(_), w) =>
      log.debug(s"SaveSnapshotSuccess: `$w`")
      stay
    // $COVERAGE-OFF$ //TODO: decide what to do
    case Event(SaveSnapshotFailure(_, _), w) =>
      log.debug(s"SaveSnapshotFailure: `$w`")
      stay
    // $COVERAGE-ON$
    case Event(ReceiveTimeout, w) => {
      log.debug("ReceiveTimeout: " + " `" + w + "`")
      context.parent ! Passivate(stopMessage = PassivateWallet)
      stay
    }
    case Event(PassivateWallet, w) => {
      passivate(w)
    }
  }
}
