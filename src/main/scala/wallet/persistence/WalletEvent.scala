package wallet.persistence

import wallet.transaction.WalletTransaction.WalletTransactionId

/**
  * Events that can happen to wallet
  */
sealed  trait WalletEvent

protected abstract class WalletBalanceEvent extends WalletEvent {
  val transactionId: WalletTransactionId
  val amount: Double
  require(amount > 0, s"Amount can't be negative: $amount")
}
final case class WalletCreationRequestAcknowledged(
  walletDetails: BasicWallet) extends WalletEvent

final case class WalletCreated(walletDetails: FullWallet) extends WalletEvent

final case class WalletBalanceIncreased(
  override val transactionId: WalletTransactionId,
  override val amount: Double) extends WalletBalanceEvent

final case class WalletBalanceDecreased(
  override val transactionId: WalletTransactionId,
  override val amount: Double) extends WalletBalanceEvent

final case class WalletTransferMoneyDebited(
  targetAccNumber: String,
  override val transactionId: WalletTransactionId,
  override val amount: Double) extends WalletBalanceEvent

final case class WalletTransferMoneyCredited(
  override val transactionId: WalletTransactionId,
  override val amount: Double) extends WalletBalanceEvent

