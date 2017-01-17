package sample.queue

import scala.concurrent.Future
import scala.util.Try

/**
  * Encapsulates all external calls for account verification / update etc
  */
trait AccountVerificationT {

  def getCreditCheck(name: String, lastName: String): Future[Try[CreditScore]]

}

final case class CreditScore(score: Int)
