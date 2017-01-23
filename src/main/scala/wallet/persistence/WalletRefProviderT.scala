package wallet.persistence

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.ClusterSharding

/**
  * Locates Wallets
  */
trait WalletRefProviderT {

  def getWalletRef()(implicit system: ActorSystem): ActorRef
}

class ClusterWalletRefProvider extends WalletRefProviderT {

  override def getWalletRef()(implicit system: ActorSystem): ActorRef =
    ClusterSharding(system).shardRegion(WalletPersistentFSM.shardName)

}
