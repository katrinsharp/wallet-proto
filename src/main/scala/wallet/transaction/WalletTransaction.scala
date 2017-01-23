package wallet.transaction

import wallet.transaction.WalletTransaction._

/**
  * Object holds all transactional stuff
  */
object WalletTransaction {

  type WalletTransactionId = String
}

final case class WalletTransaction(
  id: WalletTransactionId, amount: Double, targetAccNumber: String, numberOfTries: Int)
