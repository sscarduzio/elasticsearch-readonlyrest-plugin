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

import cats.implicits._
import monix.catnap.Semaphore
import monix.eval.Task
import monix.execution.Scheduler
import tech.beshu.ror.accesscontrol.domain.RorConfigurationIndex
import tech.beshu.ror.boot.{ReadonlyRest, StartingFailure}
import tech.beshu.ror.boot.RorInstance.RawConfigReloadError
import tech.beshu.ror.configuration.RawRorConfig
import tech.beshu.ror.es.AuditSinkService
import ConfigHash._

import scala.concurrent.duration.FiniteDuration
import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider

private[boot] class ImpersonatorsReloadableEngine(boot: ReadonlyRest,
                                                  reloadInProgress: Semaphore[Task],
                                                  rorConfigurationIndex: RorConfigurationIndex)
                                                 (implicit scheduler: Scheduler)
  extends BaseReloadableEngine(
    "test", boot, None, reloadInProgress, rorConfigurationIndex
  ) {

  def forceReloadImpersonatorsEngine(config: RawRorConfig,
                                     ttl: FiniteDuration)
                                    (implicit requestId: RequestId): Task[Either[RawConfigReloadError, Unit]] = {
    for {
      _ <- Task.delay(logger.info(s"[${requestId.show}] Reloading of ROR test settings was forced (TTL of test engine is ${ttl.toString()}) ..."))
      reloadResult <- reloadInProgress.withPermit {
        reloadEngine(config, Some(ttl)).value
      }
      _ <- Task.delay(reloadResult match {
        case Right(_) =>
          logger.info(s"[${requestId.show}] ROR $name engine (id=${config.hashString()}) reloaded!")
        case Left(RawConfigReloadError.ConfigUpToDate(oldConfig)) =>
          logger.info(s"[${requestId.show}] ROR $name engine (id=${oldConfig.hashString()}) already loaded!")
        case Left(RawConfigReloadError.ReloadingFailed(StartingFailure(message, Some(ex)))) =>
          logger.error(s"[${requestId.show}] Cannot reload ROR test settings - failure: $message", ex)
        case Left(RawConfigReloadError.ReloadingFailed(StartingFailure(message, None))) =>
          logger.error(s"[${requestId.show}] Cannot reload ROR test settings - failure: $message")
        case Left(RawConfigReloadError.RorInstanceStopped) =>
          logger.warn(s"[${requestId.show}] ROR is being stopped! Loading tests settings skipped!")
      })
    } yield reloadResult
  }

  def invalidateImpersonationEngine()
                                   (implicit requestId: RequestId): Task[Unit] = {
    invalidate()
  }
}
