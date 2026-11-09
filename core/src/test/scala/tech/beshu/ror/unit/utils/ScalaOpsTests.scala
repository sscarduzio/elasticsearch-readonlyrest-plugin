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
package tech.beshu.ror.unit.utils

import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.utils.ScalaOps.{RetryDelays, RetryPolicy, retryBackoffEither}

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.language.postfixOps
import scala.util.{Failure, Try}

class ScalaOpsTests extends AnyWordSpec {

  "retryBackoffEither" should {
    "make at most maxRetries + 1 attempts when the source returns Left" in {
      val attempts = new AtomicInteger(0)
      val result = run(attempts, policy()) { _ => Task.now(Left("absent")) }
      result should be(scala.util.Success(Left("absent")))
      attempts.get() should be(3)
    }
    "make at most maxRetries + 1 attempts when the source throws" in {
      val attempts = new AtomicInteger(0)
      val result = run(attempts, policy()) { _ => Task.raiseError(new IllegalStateException("down")) }
      result should matchPattern { case Failure(_: IllegalStateException) => }
      attempts.get() should be(3)
    }
    "make at most maxRetries + 1 attempts when the source returns Left and then throws" in {
      val attempts = new AtomicInteger(0)
      val result = run(attempts, policy()) {
        case 1 => Task.now(Left("absent"))
        case _ => Task.raiseError(new IllegalStateException("down"))
      }
      result should matchPattern { case Failure(_: IllegalStateException) => }
      attempts.get() should be(3)
    }
    "not retry an exception that the policy does not accept" in {
      val attempts = new AtomicInteger(0)
      val result = run(attempts, policy(isRetryable = _ => false)) {
        case 1 => Task.now(Left("absent"))
        case _ => Task.raiseError(new IllegalStateException("down"))
      }
      result should matchPattern { case Failure(_: IllegalStateException) => }
      attempts.get() should be(2)
    }
    "stop after the first Right" in {
      val attempts = new AtomicInteger(0)
      val result = run(attempts, policy()) {
        case 1 => Task.raiseError(new IllegalStateException("down"))
        case _ => Task.now(Right("found"))
      }
      result should be(scala.util.Success(Right("found")))
      attempts.get() should be(2)
    }
  }

  private def policy(isRetryable: Exception => Boolean = _ => true) =
    RetryPolicy(RetryDelays(1 milli, 1 milli), maxRetries = 2, isRetryableException = isRetryable)

  // `source` gets the number of the attempt, from 1.
  private def run(attempts: AtomicInteger, policy: RetryPolicy)(
      source: Int => Task[Either[String, String]]
  ): Try[Either[String, String]] =
    Try(retryBackoffEither(Task.defer(source(attempts.incrementAndGet())), policy).runSyncUnsafe(10 seconds))

}
