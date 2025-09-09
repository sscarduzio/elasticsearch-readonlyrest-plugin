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
package tech.beshu.ror.settings.loader

import cats.Show
import cats.data.EitherT
import cats.implicits.toShow
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.configuration.EsConfigBasedRorSettings.LoadFromIndexParameters

trait RetryStrategy {
  def withRetry[ERROR: Show, RESULT](operation: Task[Either[ERROR, RESULT]]): Task[Either[ERROR, RESULT]]
  def withRetryT[ERROR: Show, RESULT](operation: EitherT[Task, ERROR, RESULT]): EitherT[Task, ERROR, RESULT] =
    EitherT(withRetry(operation.value))
}

object NoRetryStrategy extends RetryStrategy {
  override def withRetry[ERROR: Show, RESULT](operation: Task[Either[ERROR, RESULT]]): Task[Either[ERROR, RESULT]] =
    operation
}

class ConfigurableRetryStrategy(params: LoadFromIndexParameters) // todo: custom case class
  extends RetryStrategy with Logging {

  override def withRetry[ERROR: Show, RESULT](operation: Task[Either[ERROR, RESULT]]): Task[Either[ERROR, RESULT]] =
    attemptWithRetry(operation, currentAttempt = 1, params.loadingAttemptsCount.value.value)

  private def attemptWithRetry[ERROR : Show, A](operation: Task[Either[ERROR, A]],
                                                currentAttempt: Int,
                                                maxAttempts: Int): Task[Either[ERROR, A]] = {
    val delay = if (currentAttempt == 1) params.loadingDelay.value.value else params.loadingAttemptsInterval.value.value
    for {
      _ <- Task.sleep(delay)
      result <- operation
      finalResult <- result match {
        case Right(value) =>
          Task.now(Right(value))
          // todo: better mesages
        case Left(error) if shouldRetry(currentAttempt, maxAttempts) =>
          logger.debug(s"Retry attempt $currentAttempt/$maxAttempts failed with: ${error.show}. Retrying in ${params.loadingAttemptsInterval.show}...")
          attemptWithRetry(operation, currentAttempt + 1, maxAttempts)
        case Left(error) =>
          logger.debug(s"Operation failed after $currentAttempt attempts: ${error.show}")
          Task.now(Left(error))
      }
    } yield finalResult
  }

  private def shouldRetry(currentAttempt: Int, maxAttempts: Int): Boolean = {
    currentAttempt < maxAttempts
  }

}
