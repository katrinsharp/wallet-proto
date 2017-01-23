package wallet.persistence

import sample.persistence.AccountActor.TransactionId

/**
  * Created by katrin on 2017-01-23.
  */
object WalletEventStreamMessages {

  final case class WalletTransactionCompletedEvent(accNumber: String, transactionId: TransactionId)

}
