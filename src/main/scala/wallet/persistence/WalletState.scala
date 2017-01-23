package wallet.persistence

import akka.persistence.fsm.PersistentFSM.FSMState

/**
  * Wallet state for Wallet FSM
  */
sealed trait WalletState extends FSMState

case object WalletPendingCreationState extends WalletState {
  override def identifier: String = "WalletPendingCreation"
}

case object WalletInactiveState extends WalletState {
  override def identifier: String = "WalletInactive"
}

case object WalletActiveState extends WalletState {
  override def identifier: String = "WalletActive"
}
