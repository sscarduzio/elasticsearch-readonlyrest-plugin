/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.settings.ror.loader

import cats.Show
import cats.data.EitherT
import cats.implicits.toShow
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.implicits.*
import tech.beshu.ror.settings.es.LoadingRorCoreStrategy.LoadingRetryStrategySettings

trait RetryStrategy {
  def withRetry[ERROR: Show, RESULT](operation: Task[Either[ERROR, RESULT]],
                                     operationDescription: String): Task[Either[ERROR, RESULT]]
  def withRetryT[ERROR: Show, RESULT](operation: EitherT[Task, ERROR, RESULT],
                                      operationDescription: String): EitherT[Task, ERROR, RESULT] =
    EitherT(withRetry(operation.value, operationDescription))
}

object NoRetryStrategy extends RetryStrategy {
  override def withRetry[ERROR: Show, RESULT](operation: Task[Either[ERROR, RESULT]],
                                              operationDescription: String): Task[Either[ERROR, RESULT]] =
    operation
}

class ConfigurableRetryStrategy(config: LoadingRetryStrategySettings)
  extends RetryStrategy with Logging {

  override def withRetry[ERROR: Show, RESULT](operation: Task[Either[ERROR, RESULT]],
                                              operationDescription: String): Task[Either[ERROR, RESULT]] =
    attemptWithRetry(operation, currentAttempt = 1, config.attemptsCount.value.value, operationDescription)

  private def attemptWithRetry[ERROR : Show, A](operation: Task[Either[ERROR, A]],
                                                currentAttempt: Int,
                                                maxAttempts: Int,
                                                operationDescription: String): Task[Either[ERROR, A]] = {
    val delay = if (currentAttempt == 1) config.delay.value.value else config.attemptsInterval.value.value
    for {
      _ <- Task.sleep(delay)
      result <- operation
      finalResult <- result match {
        case Right(value) =>
          Task.now(Right(value))
        case Left(error) if shouldRetry(currentAttempt, maxAttempts) =>
          logger.debug(s"$operationDescription - retry attempt $currentAttempt/$maxAttempts failed with: ${error.show}. Retrying in ${config.attemptsInterval.show}...")
          attemptWithRetry(operation, currentAttempt + 1, maxAttempts, operationDescription)
        case Left(error) =>
          logger.debug(s"$operationDescription - failed after $currentAttempt attempts: ${error.show}")
          Task.now(Left(error))
      }
    } yield finalResult
  }

  private def shouldRetry(currentAttempt: Int, maxAttempts: Int): Boolean = {
    currentAttempt < maxAttempts
  }

}
