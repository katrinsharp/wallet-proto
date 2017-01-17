package sample.persistence

import akka.actor.{ActorLogging, ReceiveTimeout}
import akka.cluster.sharding.ShardRegion
import akka.persistence.{PersistentActor, SaveSnapshotFailure, SaveSnapshotSuccess, SnapshotOffer}
import akka.cluster.sharding.ShardRegion.Passivate
import sample.queue.{AccountVerificationT, CreditScore}
import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}
import scala.util.{Failure, Success}
import akka.pattern.pipe

/**
  * Persistent Actor that represents Account
  */
object AccountActor {

  sealed trait AccountDetails {
    def accNumber: String
    def name: String
    def lastName: String
  }

  final case object EmptyAccountDetails
    extends AccountDetails {
    override def accNumber: String = ???
    override def name: String = ???
    override def lastName: String = ???
  }

  final case class BasicAccountDetails(override val accNumber: String, override val name: String, override val lastName: String)
    extends AccountDetails


  final case class ActiveAccountDetails(
    accNumber: String, name: String, lastName: String, score: CreditScore) extends AccountDetails

  object ActiveAccountDetails {
    def apply(account: AccountDetails, score: CreditScore): ActiveAccountDetails = ActiveAccountDetails(
      accNumber = account.accNumber,
      name = account.name,
      lastName = account.lastName,
      score = score)
  }

  final case class InactiveAccountDetails(
    accNumber: String, name: String, lastName: String, score: CreditScore, reason: String) extends AccountDetails

  object InactiveAccountDetails {
    def apply(account: AccountDetails, score: CreditScore, reason: String): InactiveAccountDetails = InactiveAccountDetails(
      accNumber = account.accNumber,
      name = account.name,
      lastName = account.lastName,
      score = score,
      reason = reason)
  }

  //TODO: having accNumber coming from outside does not make sense
  sealed trait AccountCommand {

    def accNumber: String
    final def id: String = (math.abs(accNumber.hashCode) % 100).toString //TODO: parameterize 100
  }

  val shardName = "Account"

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case cmd: AccountActor.AccountCommand => (cmd.accNumber, cmd)
  }

  val extractShardId: ShardRegion.ExtractShardId = {
    case cmd: AccountActor.AccountCommand => cmd.id
  }

  final case class CreateAccount(accDetails: AccountDetails) extends AccountCommand {
    override val accNumber = accDetails.accNumber
  }
  final case class IncreaseBalance(override val accNumber: String, amount: Double) extends AccountCommand
  final case class DecreaseBalance(override val accNumber: String, amount: Double) extends AccountCommand
  final case class GetCurrentBalance(override val accNumber: String) extends AccountCommand
  final case object AccountStop

  sealed trait AccountEvent
  final case class AccountCreationRequestAcknowledged(accountDetails: AccountDetails) extends AccountEvent
  final case class AccountCreated(accountDetails: AccountDetails) extends AccountEvent
  final case class BalanceIncreased(amount: Double) extends AccountEvent
  final case class BalanceDecreased(amount: Double) extends AccountEvent

  final case object AccountSnapshot

  final case class AccountError(description: String) extends Exception(description)

  final case class AccountPassivatedEvent(accNumber: String)
  final case class AccountTakeSnapshotEvent(accNumber: String)

  final case class AccountState(accountDetails: AccountDetails, balance: Double)

  object AccountState {
    def empty = AccountState(EmptyAccountDetails, 0.0)
  }
}

class AccountActor(accVerifier: AccountVerificationT) extends PersistentActor with ActorLogging {

  import AccountActor._

  val passivateTimeout = FiniteDuration(
    context.system.settings.config.getDuration(
      "account-actor.passivateTimeout",
      MILLISECONDS),
    MILLISECONDS)

  val snapshotDelay = FiniteDuration(
    context.system.settings.config.getDuration(
      "account-actor.snapshotDelay",
      MILLISECONDS),
    MILLISECONDS)

  val snapshotInterval = FiniteDuration(
    context.system.settings.config.getDuration(
      "account-actor.snapshotInterval",
      MILLISECONDS),
    MILLISECONDS)

  implicit val executionContext = context.dispatcher

  /*
  ** passivateTimeout should be much less than snapshotInterval otherwise timeout won't be triggered
  ** In reality, if not too much going on (not many messages), passivation will kick in
  ** If there is a lot of messages, then snapshot will be taken.
  ** Note: GetBalance will prevent passivation too, thus permitting snapshot, so same snapshots could happen.
  ** Optimization could either to remember if there is new information per messages received, or schedule a snapshot
  ** only if the is new a information or move GetBalance to PersistenceQuery
  */
  context.setReceiveTimeout(passivateTimeout)
  val snapShotSchedule = context.system.scheduler.schedule(snapshotDelay, snapshotInterval, self, AccountSnapshot)

  override def persistenceId: String = shardName + "-" + self.path.name

  private[this] var state = AccountState.empty

  def receiveRecover: Receive = {
    case SnapshotOffer(_, s: AccountState) =>
      state = s
      state.accountDetails match {
        case EmptyAccountDetails => context.become(receiveCommand)
        case basicAcc:BasicAccountDetails =>
          //TODO: who moves this actor from this state ??
          state = state.copy(
            accountDetails = InactiveAccountDetails(
              basicAcc, CreditScore(0), "Credit score check incomplete"), balance = state.balance)
          context.become(inactiveAccountReceive)
        case _: ActiveAccountDetails => context.become(activeAccountReceive)
        //TODO: who moves this actor from this state ??
        case _: InactiveAccountDetails => context.become(inactiveAccountReceive)
      }
    case AccountCreated(accountDetails) => //TODO: who moves this actor from this state ??
      InactiveAccountDetails(
        account = accountDetails,
        score = CreditScore(0),
        reason = "Credit score check incomplete")
      state = state.copy(accountDetails = accountDetails)
      context.become(inactiveAccountReceive)
    case BalanceIncreased(amount) =>
      state = state.copy(balance = state.balance + amount)
      context.become(activeAccountReceive)
    case BalanceDecreased(amount) =>
      state = state.copy(balance = state.balance - amount)
      context.become(activeAccountReceive)
  }

  def activeAccountReceive: Receive =
    changeBalanceReceive orElse maintenanceReceive orElse invalidReceive

  def changeBalanceReceive: Receive = {
    case IncreaseBalance(_, amount) if amount >= 0 =>
      persist(BalanceIncreased(amount)) { _ =>
        sender() ! Success(BalanceIncreased(amount))
        state = state.copy(balance = state.balance + amount)
      }
    case IncreaseBalance(_, amount) if amount < 0 =>
      sender() ! Failure(AccountError(
        s"Can't increase balance with negative amount: $amount"))
    case DecreaseBalance(_, amount) if amount > 0 && state.balance < amount =>
      sender() ! Failure(AccountError(
        s"Can't decrease balance, insufficient funds for: $amount"))
    case DecreaseBalance(_, amount) if amount >= 0 =>
      persist(BalanceDecreased(amount)) { _ =>
        sender() ! Success(BalanceDecreased(amount))
        state = state.copy(balance = state.balance - amount)
      }
    case DecreaseBalance(_, amount) if amount < 0 =>
      sender() ! Failure(AccountError(
        s"Can't decrease balance with negative amount: $amount"))
    case GetCurrentBalance(_) =>
      sender() ! Success(state.balance)
  }

  def initialReceive: Receive = {
    case CreateAccount(accountDetails) =>
      persist(AccountCreationRequestAcknowledged(accountDetails)) { _ =>
        sender() ! Success(AccountCreationRequestAcknowledged(accountDetails))
        accVerifier.getCreditCheck(name = accountDetails.name, lastName = accountDetails.lastName).pipeTo(self)
        state = state.copy(accountDetails = accountDetails)
        context.become(pendingCreationReceive)
      }
  }

  def pendingCreationReceive: Receive = {
    case Success(creditScore: CreditScore) if creditScore.score > 100 =>
      val accountDetails = ActiveAccountDetails(account =  state.accountDetails, score = creditScore)
      persist(AccountCreated(accountDetails)) { _ =>
        state = state.copy(accountDetails = accountDetails)
        context.become(activeAccountReceive)
      }
    case Success(creditScore: CreditScore) =>
      val accountDetails = InactiveAccountDetails(
        account = state.accountDetails, score = creditScore, reason = "low score")
      persist(AccountCreated(accountDetails)) { _ =>
        sender() ! Success(AccountCreated(accountDetails))
        state = state.copy(accountDetails = accountDetails)
        context.become(inactiveAccountReceive)
      }
    case Failure(ex) =>
      val accountDetails = InactiveAccountDetails(
        account = state.accountDetails, score = CreditScore(0), reason = ex.getMessage)
      persist(AccountCreated(accountDetails)) { _ =>
        sender() ! Success(AccountCreated(accountDetails))
        state = state.copy(accountDetails = accountDetails)
        context.become(inactiveAccountReceive)
      }
  }

  def inactiveAccountReceive: Receive = PartialFunction { cmd: Any =>
    (cmd, state.accountDetails) match {
      case (_, accDetails: InactiveAccountDetails) =>
        sender() ! Failure(AccountError(
          s"Can't perform: $cmd, inactive account due: ${accDetails.reason}"))
    }
  }

  def maintenanceReceive: Receive = {
    case ReceiveTimeout =>
      context.parent ! Passivate(stopMessage = AccountStop)
    case AccountStop => {
      //TODO: If I'm in the middle of creation, send notification to put something in Kafka queue
      context.system.eventStream.publish(AccountPassivatedEvent(state.accountDetails.accNumber))
      context.stop(self)
    }
    case AccountSnapshot => {
      context.system.eventStream.publish(AccountTakeSnapshotEvent(state.accountDetails.accNumber))
      saveSnapshot(state)
    }
    case SaveSnapshotSuccess(metadata) => //TODO: handle
    case SaveSnapshotFailure(metadata, reason) => //TODO: handle
  }

  def invalidReceive: Receive = {
    case cmd: AccountCommand =>
      println(s"$cmd !!! $state")
      sender() ! Failure(AccountError(
        s"Invalid command at this stage: $cmd"))
  }

  override def receiveCommand: Receive = initialReceive orElse maintenanceReceive orElse invalidReceive

  // $COVERAGE-OFF$ //TODO: test
  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    super.preRestart(reason, message)
  }

  override def preStart(): Unit = {
    super.preStart()
  }

  override def postStop(): Unit = {
    snapShotSchedule.cancel()
    super.postStop()
  }

  //TODO: notify FYI: [Actor is stopped]
  override def onRecoveryFailure(cause: Throwable, event: Option[Any]): Unit =
    super.onRecoveryFailure(cause, event)

  //TODO: notify FYI: [Actor is stopped]
  override def onPersistFailure(cause: Throwable, event: Any, seqNr: Long): Unit =
    super.onPersistFailure(cause, event, seqNr)

  //TODO: notify
  override def onPersistRejected(cause: Throwable, event: Any, seqNr: Long): Unit =
    super.onPersistRejected(cause, event, seqNr)
  // $COVERAGE-ON$
}
