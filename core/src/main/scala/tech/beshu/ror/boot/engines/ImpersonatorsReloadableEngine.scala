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
package tech.beshu.ror.boot.engines

import monix.catnap.Semaphore
import monix.eval.Task
import monix.execution.Scheduler
import tech.beshu.ror.accesscontrol.domain.RorConfigurationIndex
import tech.beshu.ror.boot.ReadonlyRest
import tech.beshu.ror.boot.RorInstance.RawConfigReloadError
import tech.beshu.ror.configuration.RawRorConfig
import tech.beshu.ror.es.AuditSinkService

import scala.concurrent.duration.FiniteDuration

private[boot] class ImpersonatorsReloadableEngine(boot: ReadonlyRest,
                                                  reloadInProgress: Semaphore[Task],
                                                  rorConfigurationIndex: RorConfigurationIndex,
                                                  auditSink: AuditSinkService)
                                                 (implicit scheduler: Scheduler)
  extends BaseReloadableEngine("test", boot, None, reloadInProgress, rorConfigurationIndex, auditSink) {

  def forceReloadImpersonatorsEngine(config: RawRorConfig,
                                     ttl: Option[FiniteDuration]): Task[Either[RawConfigReloadError, Unit]] = {
    reloadInProgress.withPermit {
      for {
        _ <- Task.delay(logger.debug("Reloading of test settings was forced"))
        reloadResult <- reloadEngine(config, ttl).value
      } yield reloadResult
    }
  }

  def invalidateImpersonationEngine(): Task[Unit] = {
    reloadInProgress.withPermit {
      for {
        _ <- Task.delay(logger.debug("Test settings will be invalidated ... "))
        _ <- stop()
      } yield ()
    }
  }
}
