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
package tech.beshu.ror.boot

import monix.execution.Scheduler
import monix.execution.Scheduler.global

object RorSchedulers {

  object Implicits {
    implicit val mainScheduler: Scheduler = RorSchedulers.scheduler
    implicit val rorRestApiScheduler: Scheduler = RorSchedulers.restApiScheduler
  }

  val scheduler: Scheduler = {
    // This is hack for this specific version of java(1.8.0_262). There were permission issues when default scheduler was used.
    // Java 1.8.0_265 have that fixed, but we have found that using ThreadPoolExecutor instead of ForkJoinPool solves permission
    // issues with version 262.
    if (System.getProperty("java.version") == "1.8.0_262") cachedScheduler
    else global
  }

  private lazy val cachedScheduler = Scheduler.cached(
    "CustomThreadPoolExecutor",
    getInt("scala.concurrent.context.minThreads", "1"),
    getInt("scala.concurrent.context.maxThreads", "x1")
  )

  val blockingScheduler: Scheduler = Scheduler.io("blocking-index-content-provider")

  val restApiScheduler: Scheduler = Scheduler.fixedPool("ror-rest-api-executor", 10)

  val ldapUnboundIdBlockingScheduler: Scheduler = Scheduler.cached("unboundid-executor", 10, 100)

  private def getInt(name: String, default: String) = (try System.getProperty(name, default) catch {
    case _: SecurityException => default
  }) match {
    case s if s.charAt(0) == 'x' => (Runtime.getRuntime.availableProcessors * s.substring(1).toDouble).ceil.toInt
    case other => other.toInt
  }
}
