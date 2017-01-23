package wallet.persistence

import akka.actor.Props
import akka.cluster.sharding.ShardRegion
import akka.persistence.fsm.PersistentFSM

import scala.reflect._
import scala.util.{Failure, Success}
import akka.pattern.pipe
import wallet.persistence.WalletEventStreamMessages.WalletTransactionCompletedEvent
import wallet.services.{CreditScore, CreditVerificationT, WalletCreationNotifierT}
import wallet.transaction.{InsufficientWalletFunds, InvalidWalletTransaction, WalletTransaction}

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

  //TODO: implement recovery
  override def applyEvent(domainEvent: WalletEvent, currentWallet: Wallet): Wallet = {
    (domainEvent, currentWallet) match {
      case (ev: WalletCreationRequestAcknowledged, EmptyWallet) => ev.walletDetails
      case (ev: WalletCreated, _: BasicWallet) => ev.walletDetails
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
        activeWallet
      // It could become Inactive meanwhile so FullWallet instead of ActiveWallet
      case (ev: WalletTransferMoneyCredited, activeWallet: FullWallet) =>
        //TODO: report if transaction didn't exist
        activeWallet.removeTransaction(ev.transactionId)
        context.system.eventStream.publish(
          WalletTransactionCompletedEvent(activeWallet.accNumber, ev.transactionId))
        activeWallet
    }
  }

  override def persistenceId: String = shardName + "-" + self.path.name

  startWith(WalletPendingCreationState, EmptyWallet)

  when(WalletPendingCreationState) {
    case Event(CreateWallet(accNumber, name, lastName), EmptyWallet) =>
      accVerifier.getCreditCheck(name = name, lastName = lastName)
        .recover{case ex => Failure(ex)}.pipeTo(self)
      val ev = WalletCreationRequestAcknowledged(BasicWallet(accNumber, name, lastName))
      stay.applying(ev).replying(ev)
    case Event(Success(score: CreditScore), currentWallet: BasicWallet) =>
      if(score.score > 100) {
        walletCreationNotifier.notifySuccess(currentWallet.accNumber)
        goto(WalletActiveState)
          .applying(WalletCreated(ActiveWallet(currentWallet, score)))
      }
      else {
        val reason = s"Score is too low: ${score.score}"
        walletCreationNotifier.notifyFailure(currentWallet.accNumber, reason)
        goto(WalletInactiveState)
          .applying(WalletCreated(InactiveWallet(currentWallet, score, reason)))
      }
    case Event(Failure(ex), currentWallet: BasicWallet) =>
      goto(WalletInactiveState)
        .applying(WalletCreated(InactiveWallet(currentWallet, CreditScore.empty, ex.getMessage)))
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
    case Event(cmd: WalletCommand, _) => stay.replying(InvalidWalletTransaction(cmd))
  }
}
