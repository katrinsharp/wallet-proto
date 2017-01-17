package sample.queue

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.ClusterSharding
import sample.persistence.AccountActor

/**
  * Locates AccountActor
  */
trait AccountActorProviderT {

  def getAccountActor()(implicit system: ActorSystem): ActorRef
}

trait ClusterAccountActorProvider extends AccountActorProviderT {

  override def getAccountActor()(implicit system: ActorSystem): ActorRef =
    ClusterSharding(system).shardRegion(AccountActor.shardName)

}
