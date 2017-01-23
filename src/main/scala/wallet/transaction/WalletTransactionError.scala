package wallet.transaction

import wallet.persistence.WalletCommand

/**
  * Possible transaction errors
  */
trait WalletTransactionError {
  def message: String
}

case object InsufficientWalletFunds extends WalletTransactionError {
  override val message = "Not enough funds in wallet"
}

case class InvalidWalletTransaction(cmd: WalletCommand) extends WalletTransactionError {
  override val message = s"Invalid wallet transaction: $cmd"
}


