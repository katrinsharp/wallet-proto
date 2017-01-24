package wallet.persistence

import wallet.transaction.WalletTransaction.WalletTransactionId

/**
  * Messages published to Akka event stream
  */
object WalletEventStreamMessages {

  final case class WalletTransactionCompletedEvent(
    accNumber: String,
    transactionId: WalletTransactionId)

  final case class WalletPassivatedEvent(accNumber: String)

  final case class WalletRecoveryCompletedEvent(accNumber: String)

  final case class WalletSnapshotTakenEvent(accNumber: String)
}
