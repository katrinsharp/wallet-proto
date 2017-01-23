package wallet.persistence

import wallet.transaction.WalletTransaction.WalletTransactionId

/**
  * Created by katrin on 2017-01-23.
  */
object WalletEventStreamMessages {

  final case class WalletTransactionCompletedEvent(
    accNumber: String,
    transactionId: WalletTransactionId)
}
