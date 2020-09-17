package tech.beshu.ror.utils

import monix.execution.Scheduler
import monix.execution.Scheduler.Implicits.global

object SchedulerHelper {
  private def getInt(name: String, default: String) = (try System.getProperty(name, default) catch {
    case e: SecurityException => default
  }) match {
    case s if s.charAt(0) == 'x' => (Runtime.getRuntime.availableProcessors * s.substring(1).toDouble).ceil.toInt
    case other => other.toInt
  }

  private val cachedScheduler = Scheduler.cached("CustomThreadPoolExecutor", getInt("scala.concurrent.context.minThreads", "1"), getInt("scala.concurrent.context.maxThreads", "x1"))

  def scheduler =
    // This is hack for this specific version of java(1.8.0_262). There were permission issues when default scheduler was used.
    // Java 1.8.0_265 have that fixed, but we have found that using ThreadPoolExecutor instead of ForkJoinPool solves permission
    // issues with version 262.
    if(System.getProperty("java.version") == "1.8.0_262") cachedScheduler
    else global
}
