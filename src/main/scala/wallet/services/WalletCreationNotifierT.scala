package wallet.services

/**
  * Will be called for each successful / failed account creation
  * IMPORTANT: Shouldn't take more than X time, otherwise will block the wallet
  */
trait WalletCreationNotifierT {

  def notifySuccess(walletNumber: String): Unit
  def notifyFailure(walletNumber: String, reason: String): Unit

}
