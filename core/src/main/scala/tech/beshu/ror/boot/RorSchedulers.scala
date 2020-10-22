package tech.beshu.ror.boot

import monix.execution.Scheduler
import monix.execution.Scheduler.global

object RorSchedulers {

  object Implicits {
    implicit val mainScheduler: Scheduler = RorSchedulers.scheduler
    implicit val adminApiScheduler: Scheduler = RorSchedulers.adminRestApiScheduler
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

  val adminRestApiScheduler: Scheduler = Scheduler.fixedPool("admin-rest-api-executor", 5)

  val ldapUnboundIdBlockingScheduler: Scheduler = Scheduler.cached("unboundid-executor", 10, 50)

  private def getInt(name: String, default: String) = (try System.getProperty(name, default) catch {
    case e: SecurityException => default
  }) match {
    case s if s.charAt(0) == 'x' => (Runtime.getRuntime.availableProcessors * s.substring(1).toDouble).ceil.toInt
    case other => other.toInt
  }
}
