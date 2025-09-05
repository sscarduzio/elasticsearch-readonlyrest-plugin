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

import cats.data.EitherT
import monix.catnap.Semaphore
import monix.eval.Task
import monix.execution.Scheduler
import tech.beshu.ror.SystemContext
import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.boot.ReadonlyRest
import tech.beshu.ror.boot.ReadonlyRest.*
import tech.beshu.ror.boot.RorInstance.*
import tech.beshu.ror.boot.RorInstance.IndexSettingsReloadWithUpdateError.{IndexSettingsSavingError, ReloadError}
import tech.beshu.ror.boot.RorInstance.RawSettingsReloadError.{ReloadingFailed, RorInstanceStopped, SettingsUpToDate}
import tech.beshu.ror.boot.engines.BaseReloadableEngine.InitialEngine
import tech.beshu.ror.boot.engines.SettingsHash.*
import tech.beshu.ror.configuration.{EsConfigBasedRorSettings, RawRorSettings}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.settings.source.{IndexSettingsSource, MainSettingsIndexSource}
import tech.beshu.ror.utils.ScalaOps.value

private[boot] class MainSettingsBasedReloadableEngine private(boot: ReadonlyRest,
                                                              esConfigBasedRorSettings: EsConfigBasedRorSettings,
                                                              initialEngine: (Engine, RawRorSettings),
                                                              reloadInProgress: Semaphore[Task],
                                                              settingsSource: IndexSettingsSource[RawRorSettings])
                                                             (implicit systemContext: SystemContext,
                                                              scheduler: Scheduler)
  extends BaseReloadableEngine(
    name = "main",
    boot = boot,
    esConfigBasedRorSettings = esConfigBasedRorSettings,
    initialEngine = InitialEngine.Configured(engine = initialEngine._1, settings = initialEngine._2, expiration = None),
    reloadInProgress = reloadInProgress
  ) {

  def forceReloadAndSave(settings: RawRorSettings)
                        (implicit requestId: RequestId): Task[Either[IndexSettingsReloadWithUpdateError, Unit]] = {
    for {
      _ <- Task.delay(logger.info(s"[${requestId.show}] Reloading of provided settings was forced (new engine id=${settings.hashString()}) ..."))
      reloadResult <- reloadInProgress.withPermit {
        value {
          for {
            _ <- reloadEngine(settings).leftMap(IndexSettingsReloadWithUpdateError.ReloadError.apply)
            _ <- saveSettings(settings)
          } yield ()
        }
      }
      _ <- Task.delay(reloadResult match {
        case Right(_) =>
          logger.info(s"[${requestId.show}] ROR ${name.show} engine (id=${settings.hashString().show}) settings reloaded!")
        case Left(ReloadError(SettingsUpToDate(oldSettings))) =>
          logger.info(s"[${requestId.show}] ROR ${name.show} engine (id=${oldSettings.hashString().show}) already loaded!")
        case Left(ReloadError(ReloadingFailed(StartingFailure(message, Some(ex))))) =>
          logger.error(s"[${requestId.show}] [${settings.hashString()}] Cannot reload ROR settings - failure: ${message.show}", ex)
        case Left(ReloadError(ReloadingFailed(StartingFailure(message, None)))) =>
          logger.error(s"[${requestId.show}] Cannot reload ROR settings - failure: ${message.show}")
        case Left(ReloadError(RorInstanceStopped)) =>
          logger.warn(s"[${requestId.show}] ROR is being stopped! Loading main settings skipped!")
        case Left(IndexSettingsSavingError(_)) => // todo: SavingIndexSettingsError.CannotSaveSettings)) =>
          // todo: invalidate created core?
          logger.warn(s"[${requestId.show}] ROR is being stopped! Loading main settings skipped!")
      })
    } yield reloadResult
  }

  def forceReloadFromIndex()
                          (implicit requestId: RequestId): Task[Either[IndexSettingsReloadError, Unit]] = {
    for {
      _ <- Task.delay(logger.info(s"[${requestId.show}] Reloading of in-index settings was forced ..."))
      reloadResult <- reloadEngineUsingIndexSettings()
      _ <- Task.delay(reloadResult match {
        case Right(settings) =>
          logger.info(s"[${requestId.show}] ROR ${name.show} engine (id=${settings.hashString().show}) settings reloaded!")
        case Left(IndexSettingsReloadError.ReloadError(SettingsUpToDate(settings))) =>
          logger.info(s"[${requestId.show}] ROR ${name.show} engine (id=${settings.hashString().show}) already loaded!")
        case Left(IndexSettingsReloadError.ReloadError(ReloadingFailed(StartingFailure(message, Some(ex))))) =>
          logger.error(s"[${requestId.show}] Cannot reload ROR settings - failure: ${message.show}", ex)
        case Left(IndexSettingsReloadError.ReloadError(ReloadingFailed(StartingFailure(message, None)))) =>
          logger.error(s"[${requestId.show}] Cannot reload ROR settings - failure: ${message.show}")
        case Left(IndexSettingsReloadError.ReloadError(RorInstanceStopped)) =>
          logger.warn(s"[${requestId.show}] ROR is being stopped! Loading main settings skipped!")
        case Left(IndexSettingsReloadError.IndexLoadingSettingsError(error)) =>
          logger.error(s"[${requestId.show}] Cannot reload ROR settings - failure: ") // ${error.show}") // todo:
      })
    } yield reloadResult.map(_ => ())
  }

  private def reloadEngineUsingIndexSettings()
                                            (implicit requestId: RequestId): Task[Either[IndexSettingsReloadError, RawRorSettings]] = {
    reloadInProgress.withPermit {
      reloadEngineUsingIndexSettingsWithoutPermit()
    }
  }

  private[boot] def reloadEngineUsingIndexSettingsWithoutPermit()
                                                               (implicit requestId: RequestId): Task[Either[IndexSettingsReloadError, RawRorSettings]] = {
    val result = for {
      newSettings <- loadRorSettingFromIndex()
      _ <- reloadEngine(newSettings)
        .leftMap(IndexSettingsReloadError.ReloadError.apply)
        .leftWiden[IndexSettingsReloadError]
    } yield newSettings
    result.value
  }

  private def loadRorSettingFromIndex() = {
    EitherT(settingsSource.load())
      .leftMap(IndexSettingsReloadError.IndexLoadingSettingsError.apply)
  }

  private def saveSettings(settings: RawRorSettings): EitherT[Task, IndexSettingsReloadWithUpdateError, Unit] = {
    EitherT(settingsSource.save(settings))
      .leftMap(IndexSettingsReloadWithUpdateError.IndexSettingsSavingError.apply)
  }

}
object MainSettingsBasedReloadableEngine {

  final class Creator(settingsSource: MainSettingsIndexSource) {

    def create(boot: ReadonlyRest,
               esConfigBasedRorSettings: EsConfigBasedRorSettings,
               initialEngine: (Engine, RawRorSettings),
               reloadInProgress: Semaphore[Task])
              (implicit systemContext: SystemContext,
               scheduler: Scheduler): MainSettingsBasedReloadableEngine = {
      new MainSettingsBasedReloadableEngine(boot, esConfigBasedRorSettings, initialEngine, reloadInProgress, settingsSource)
    }
  }
}