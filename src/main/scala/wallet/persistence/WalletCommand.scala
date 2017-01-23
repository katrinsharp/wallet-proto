package wallet.persistence

import wallet.transaction.WalletTransaction.WalletTransactionId

/**
  * Commands accepted by Wallet persistent actor
  */
//TODO: having accNumber coming from outside makes sense?
sealed trait WalletCommand {
  def accNumber: String
  //TODO: parameterize 100
  final def id: String = (math.abs(accNumber.hashCode) % 100).toString
}

final case class CreateWallet(
  override val accNumber: String,
  name: String,
  lastName: String) extends WalletCommand

protected abstract class WalletBalanceCommand extends WalletCommand {
  val transactionId: WalletTransactionId
  val amount: Double
  require(amount > 0, s"Amount can't be negative: $amount")
}

final case class IncreaseWalletBalance(
  override val accNumber: String,
  override val amount: Double,
  override val transactionId: WalletTransactionId)
  extends WalletBalanceCommand

final case class DecreaseWalletBalance(
  override val accNumber: String,
  override val amount: Double,
  override val transactionId: WalletTransactionId)
  extends WalletBalanceCommand

final case class TransferBalanceBetweenWallets(
  override val accNumber: String,
  override val amount: Double, targetAccNumber: String,
  override val transactionId: WalletTransactionId) extends WalletBalanceCommand

final case class CreditWalletBalance(
  override val accNumber: String,
  override val amount: Double,
  override val transactionId: WalletTransactionId) extends WalletBalanceCommand

final case class GetCurrentWalletBalance(override val accNumber: String)
  extends WalletCommand

