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
import cats.data.EitherT
import monix.catnap.Semaphore
import monix.eval.Task
import monix.execution.Scheduler
import tech.beshu.ror.accesscontrol.domain.RorConfigurationIndex
import tech.beshu.ror.boot.RorInstance.{IndexConfigReloadError, IndexConfigReloadWithUpdateError}
import tech.beshu.ror.boot.{Engine, ReadonlyRest}
import tech.beshu.ror.configuration.{IndexConfigManager, RawRorConfig}
import tech.beshu.ror.es.AuditSinkService
import tech.beshu.ror.utils.ScalaOps.value

private [boot] class MainReloadableEngine(boot: ReadonlyRest,
                                          initialEngine: (Engine, RawRorConfig),
                                          reloadInProgress: Semaphore[Task],
                                          indexConfigManager: IndexConfigManager,
                                          rorConfigurationIndex: RorConfigurationIndex,
                                          auditSink: AuditSinkService)
                                         (implicit scheduler: Scheduler)
  extends BaseReloadableEngine("main", boot, Some(initialEngine), reloadInProgress, rorConfigurationIndex, auditSink) {

  def forceReloadAndSave(config: RawRorConfig): Task[Either[IndexConfigReloadWithUpdateError, Unit]] = {
    for {
      _ <- Task.delay(logger.debug("Reloading of provided settings was forced"))
      reloadResult <- reloadInProgress.withPermit {
        value {
          for {
            _ <- reloadEngine(config).leftMap(IndexConfigReloadWithUpdateError.ReloadError.apply)
            _ <- saveConfig(config)
          } yield ()
        }
      }
    } yield reloadResult
  }

  def forceReloadFromIndex(): Task[Either[IndexConfigReloadError, Unit]] = {
    reloadInProgress.withPermit {
      for {
        _ <- Task.delay(logger.debug("Reloading of in-index settings was forced"))
        reloadResult <- reloadEngineUsingIndexConfig()
      } yield reloadResult
    }
  }

  def reloadEngineUsingIndexConfig(): Task[Either[IndexConfigReloadError, Unit]] = {
    val result = for {
      newConfig <- EitherT(loadRorConfigFromIndex())
      _ <- reloadEngine(newConfig)
        .leftMap(IndexConfigReloadError.ReloadError.apply)
        .leftWiden[IndexConfigReloadError]
    } yield ()
    result.value
  }

  private def saveConfig(newConfig: RawRorConfig): EitherT[Task, IndexConfigReloadWithUpdateError, Unit] = EitherT {
    for {
      saveResult <- indexConfigManager.save(newConfig, rorConfigurationIndex)
    } yield saveResult.left.map(IndexConfigReloadWithUpdateError.IndexConfigSavingError.apply)
  }

  private def loadRorConfigFromIndex() = {
    indexConfigManager
      .load(rorConfigurationIndex)
      .map(_.left.map(IndexConfigReloadError.LoadingConfigError.apply))
  }

}
