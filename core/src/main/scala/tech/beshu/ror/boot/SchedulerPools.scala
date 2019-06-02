package tech.beshu.ror.boot

import monix.execution.Scheduler

object SchedulerPools {

  val adminRestApiScheduler: Scheduler = Scheduler.fixedPool("admin-rest-api", 5)
}
