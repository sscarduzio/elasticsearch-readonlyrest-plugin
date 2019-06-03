package tech.beshu.ror.boot

import monix.execution.Scheduler

object SchedulerPools {

  implicit val adminRestApiScheduler: Scheduler = Scheduler.fixedPool("admin-rest-api", 5)
}
