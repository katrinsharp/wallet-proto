package wallet.persistence

import wallet.services.CreditScore
import wallet.transaction.WalletTransaction
import wallet.transaction.WalletTransaction.WalletTransactionId

/**
  * Wallet details, which is part of Wallet state
  */
sealed trait Wallet {
  def accNumber: String
  def name: String
  def lastName: String
}

final case object EmptyWallet
  extends Wallet {
  override def accNumber: String = throw new IllegalAccessException("Empty Wallet")
  override def name: String = throw new IllegalAccessException("Empty Wallet")
  override def lastName: String = throw new IllegalAccessException("Empty Wallet")
}

final case class BasicWallet(
  override val accNumber: String,
  override val name: String,
  override val lastName: String)
  extends Wallet

sealed trait WithWalletCreditScore {
  def score: CreditScore
}

sealed trait WithWalletBalance {
  val balance: Double = 0.0
}

//NOTE: Not thread-safe
//TODO: UGLY!!!
trait WalletTransactions {

  private[this] var transactions: Map[WalletTransactionId, WalletTransaction] =
    Map.empty[WalletTransactionId, WalletTransaction]

  def addTransaction(transaction: WalletTransaction): Unit = {
    transactions = transactions + (transaction.id -> transaction)
  }

  def removeTransaction(transactionId: WalletTransactionId): Option[WalletTransaction] = {
    val maybeTransaction = transactions.get(transactionId)
    transactions = transactions - transactionId
    maybeTransaction
  }
}

protected abstract class FullWallet
  extends Wallet with WithWalletCreditScore with WithWalletBalance with WalletTransactions

final case class ActiveWallet(
  override val accNumber: String,
  override val name: String,
  override val lastName: String,
  override val score: CreditScore,
  override val balance: Double
  )
  extends FullWallet

object ActiveWallet {
  def apply(account: Wallet, score: CreditScore): ActiveWallet =
    ActiveWallet(
      accNumber = account.accNumber,
      name = account.name,
      lastName = account.lastName,
      score = score,
      balance = 0.0)
}

final case class InactiveWallet(
  override val accNumber: String,
  override val name: String,
  override val lastName: String,
  score: CreditScore,
  override val balance: Double,
  reason: String) extends FullWallet

object InactiveWallet {
  def apply(account: Wallet,
            score: CreditScore,
            reason: String): InactiveWallet = InactiveWallet(
    accNumber = account.accNumber,
    name = account.name,
    lastName = account.lastName,
    score = score,
    balance = 0.0,
    reason = reason)
}

