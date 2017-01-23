package wallet.services

import scala.concurrent.Future
import scala.util.Try

/**
  * Encapsulates all external calls for credit verification
  */
trait CreditVerificationT {

  def getCreditCheck(name: String, lastName: String): Future[Try[CreditScore]]

}

final case class CreditScore(score: Int)

object CreditScore {
  val empty = CreditScore(0)
}
