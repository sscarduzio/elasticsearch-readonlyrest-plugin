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
import cats.implicits.*
import monix.catnap.Semaphore
import monix.eval.Task
import monix.execution.atomic.{Atomic, AtomicAny}
import monix.execution.{Cancelable, Scheduler}
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.SystemContext
import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.boot.ReadonlyRest
import tech.beshu.ror.boot.ReadonlyRest.Engine
import tech.beshu.ror.boot.RorInstance.RawSettingsReloadError
import tech.beshu.ror.boot.engines.BaseReloadableEngine.*
import tech.beshu.ror.boot.engines.BaseReloadableEngine.EngineState.NotStartedYet
import tech.beshu.ror.boot.engines.SettingsHash.*
import tech.beshu.ror.configuration.{EsConfigBasedRorSettings, RawRorSettings}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.DurationOps.*

import java.time.Instant
import scala.concurrent.duration.*
import scala.language.postfixOps

private[engines] abstract class BaseReloadableEngine(val name: String,
                                                     boot: ReadonlyRest,
                                                     esConfigBasedRorSettings: EsConfigBasedRorSettings,
                                                     initialEngine: InitialEngine,
                                                     reloadInProgress: Semaphore[Task])
                                                    (implicit systemContext: SystemContext,
                                                     scheduler: Scheduler)
  extends Logging {

  import BaseReloadableEngine.EngineUpdateType

  private val currentEngine: Atomic[EngineState] = AtomicAny[EngineState](
    initialEngine match {
      case InitialEngine.Configured(engine, settings, expiration) =>
        logger.info(s"ROR ${name.show} engine (id=${settings.hashString().show}) was initiated (${engine.core.accessControl.description.show}).")
        stateFromInitial(EngineWithSettings(engine, settings, expiration))(RequestId(systemContext.uuidProvider.random.toString))
      case InitialEngine.NotConfigured =>
        EngineState.NotStartedYet(recentSettings = None, recentExpiration = None)
      case InitialEngine.Invalidated(settings, expiration) =>
        EngineState.NotStartedYet(recentSettings = Some(settings), recentExpiration = Some(expiration))
    }
  )

  def engine: Option[Engine] = currentEngine.get() match {
    case EngineState.NotStartedYet(_, _) => None
    case EngineState.Working(engineWithSetting, _) => Some(engineWithSetting.engine)
    case EngineState.Stopped => None
  }

  def stop()
          (implicit requestId: RequestId): Task[Unit] = {
    reloadInProgress.withPermit {
      for {
        state <- Task.delay(currentEngine.getAndSet(EngineState.Stopped))
        _ <- Task.delay {
          state match {
            case EngineState.NotStartedYet(_, _) =>
            case working@EngineState.Working(engineWithSetting, _) =>
              logger.info(s"[${requestId.show}] ROR ${name.show} engine (id=${engineWithSetting.settings.hashString().show}) will be stopped ...")
              stopNow(working)
            case EngineState.Stopped =>
          }
        }
      } yield ()
    }
  }

  protected def invalidate(keepPreviousSettings: Boolean)
                          (implicit requestId: RequestId): Task[Option[InvalidationResult]] = {
    Task.delay {
      val invalidationTimestamp = systemContext.clock.instant()
      val previous = currentEngine.getAndTransform {
        case notStarted: EngineState.NotStartedYet =>
          if (keepPreviousSettings) {
            notStarted
          } else {
            EngineState.NotStartedYet(recentSettings = None, recentExpiration = None)
          }
        case oldWorkingEngine@EngineState.Working(engineWithSetting, _) =>
          logger.info(s"[${requestId.show}] ROR ${name.show} engine (id=${engineWithSetting.settings.hashString().show}) will be invalidated ...")
          stopEarly(oldWorkingEngine)
          if (keepPreviousSettings) {
            EngineState.NotStartedYet(
              recentSettings = Some(engineWithSetting.settings),
              recentExpiration = engineWithSetting.expiration.map {
                recentSettings => recentSettings.copy(validTo = invalidationTimestamp)
              }
            )
          } else {
            EngineState.NotStartedYet(recentSettings = None, recentExpiration = None)
          }
        case EngineState.Stopped =>
          EngineState.Stopped
      }

      previous match {
        case _: EngineState.NotStartedYet => None
        case EngineState.Working(engineWithSetting, _) =>
          engineWithSetting.expiration.map(expiration =>
            InvalidationResult(engineWithSetting.settings, expiration.copy(validTo = invalidationTimestamp))
          )
        case EngineState.Stopped => None
      }
    }
  }

  protected final def currentEngineState: EngineState = currentEngine.get()

  protected def reloadEngine(rorSettings: RawRorSettings)
                            (implicit requestId: RequestId): EitherT[Task, RawSettingsReloadError, Unit] = {
    reloadEngineWithoutTtl(rorSettings)
  }

  protected def reloadEngine(newSettings: RawRorSettings,
                             newSettingsEngineTtl: PositiveFiniteDuration)
                            (implicit requestId: RequestId): EitherT[Task, RawSettingsReloadError, ReloadResult] = {
    reloadEngineWithConfiguredTtl(newSettings, UpdatedExpiration.ByTtl(newSettingsEngineTtl))
  }

  protected def reloadEngine(newSettings: RawRorSettings,
                             newSettingsExpirationTime: Instant,
                             configuredTtl: PositiveFiniteDuration)
                            (implicit requestId: RequestId): EitherT[Task, RawSettingsReloadError, Unit] = {
    isStillValid(newSettingsExpirationTime) match {
      case RemainingEngineTime.Valid(_) =>
        reloadEngineWithConfiguredTtl(newSettings, UpdatedExpiration.ToTime(newSettingsExpirationTime, configuredTtl))
          .map(_ => ())
      case RemainingEngineTime.Expired =>
        EitherT.right {
          Task.delay {
            val newExpiration = EngineExpiration(ttl = configuredTtl, validTo = newSettingsExpirationTime)
            currentEngine.transform {
              case _: EngineState.NotStartedYet =>
                NotStartedYet(recentSettings = Some(newSettings), recentExpiration = Some(newExpiration))
              case working: EngineState.Working =>
                logger.info(s"[${requestId.show}] ROR ${name.show} engine (id=${working.engineWithSetting.settings.hashString().show}) will be invalidated ...")
                stopEarly(working)
                NotStartedYet(recentSettings = Some(newSettings), recentExpiration = Some(newExpiration))
              case EngineState.Stopped =>
                EngineState.Stopped
            }
          }
        }
    }
  }

  private def reloadEngineWithConfiguredTtl(newSettings: RawRorSettings,
                                            expiration: UpdatedExpiration)
                                           (implicit requestId: RequestId): EitherT[Task, RawSettingsReloadError, ReloadResult] = {
    for {
      engineUpdateType <- checkUpdateType(newSettings, expiration)
      expiration <- engineUpdateType match {
        case EngineUpdateType.UpdateSettings =>
          runReload(newSettings, Some(expiration)).map { engine =>
            ReloadResult(engine.engine, engine.expiration.get)
          }
        case EngineUpdateType.UpdateSettingsTtl =>
          updateEngineExpiration(expiration)
        case EngineUpdateType.SettingsAndTtlUpToDate(engine, expiration) =>
          EitherT.right[RawSettingsReloadError](Task.now(ReloadResult(engine, expiration)))
      }
    } yield expiration
  }

  private def reloadEngineWithoutTtl(rorSettings: RawRorSettings)
                                    (implicit requestId: RequestId): EitherT[Task, RawSettingsReloadError, Unit] = {
    for {
      _ <- canBeReloaded(rorSettings)
      _ <- runReload(rorSettings, None)
    } yield ()
  }

  private def runReload(rorSettings: RawRorSettings,
                        expiration: Option[UpdatedExpiration])
                       (implicit requestId: RequestId): EitherT[Task, RawSettingsReloadError, EngineWithSettings] = {
    for {
      newEngineWithSettings <- reloadWith(rorSettings, expiration)
      _ <- replaceCurrentEngine(newEngineWithSettings)
    } yield newEngineWithSettings
  }

  private def checkUpdateType(newSettings: RawRorSettings,
                              newExpiration: UpdatedExpiration): EitherT[Task, RawSettingsReloadError, EngineUpdateType] = {
    EitherT {
      Task.delay {
        currentEngine.get() match {
          case EngineState.NotStartedYet(_, _) =>
            Right(EngineUpdateType.UpdateSettings)
          case EngineState.Working(EngineWithSettings(_, currentSettings, _), _) if currentSettings != newSettings =>
            Right(EngineUpdateType.UpdateSettings)
          case EngineState.Working(EngineWithSettings(engine, _, engineExpiration), _) =>
            checkIfExpirationHasChanged(engine, newExpiration, engineExpiration)
          case EngineState.Stopped =>
            Left(RawSettingsReloadError.RorInstanceStopped)
        }
      }
    }
  }

  private def checkIfExpirationHasChanged(engine: Engine,
                                          newExpiration: UpdatedExpiration,
                                          engineExpiration: Option[EngineExpiration]) = {
    newExpiration match {
      case UpdatedExpiration.ByTtl(_) =>
        Right(EngineUpdateType.UpdateSettingsTtl)
      case UpdatedExpiration.ToTime(validTo, configuredTtl) =>
        val providedExpiration = EngineExpiration(configuredTtl, validTo)
        if (engineExpiration.contains(providedExpiration)) {
          Right(EngineUpdateType.SettingsAndTtlUpToDate(engine, providedExpiration))
        } else {
          Right(EngineUpdateType.UpdateSettingsTtl)
        }
    }
  }

  private def canBeReloaded(newSettings: RawRorSettings): EitherT[Task, RawSettingsReloadError, Unit] = {
    EitherT {
      Task.delay {
        currentEngine.get() match {
          case EngineState.NotStartedYet(_, _) =>
            Right(())
          case EngineState.Working(EngineWithSettings(_, currentSettings, _), _) if currentSettings != newSettings =>
            Right(())
          case EngineState.Working(EngineWithSettings(_, currentSettings, _), _) =>
            Left(RawSettingsReloadError.SettingsUpToDate(currentSettings))
          case EngineState.Stopped =>
            Left(RawSettingsReloadError.RorInstanceStopped)
        }
      }
    }
  }

  private def reloadWith(rorSettings: RawRorSettings,
                         expiration: Option[UpdatedExpiration]): EitherT[Task, RawSettingsReloadError, EngineWithSettings] = {
    EitherT(boot.loadRorEngine(rorSettings, esConfigBasedRorSettings.settingsIndex))
      .map { engine =>
        EngineWithSettings(
          engine = engine,
          settings = rorSettings,
          expiration = expiration.map(engineExpiration)
        )
      }
      .leftMap(RawSettingsReloadError.ReloadingFailed.apply)
  }

  private def engineExpiration(expiration: UpdatedExpiration) = {
    expiration match {
      case UpdatedExpiration.ByTtl(ttl) =>
        EngineExpiration(ttl = ttl, validTo = systemContext.clock.instant().plusMillis(ttl.value.toMillis))
      case UpdatedExpiration.ToTime(validTo, configuredTtl) =>
        EngineExpiration(ttl = configuredTtl, validTo = validTo)
    }
  }

  private def replaceCurrentEngine(newEngineWithSettings: EngineWithSettings)
                                  (implicit requestId: RequestId): EitherT[Task, RawSettingsReloadError, Unit] = {
    EitherT {
      Task.delay {
        val oldEngineState = currentEngine.getAndTransform {
          case _: EngineState.NotStartedYet =>
            logger.info(s"[${requestId.show}] ROR ${name.show} engine (id=${newEngineWithSettings.settings.hashString().show}) is going to be used ...")
            workingStateFrom(newEngineWithSettings)
          case oldWorkingEngine@EngineState.Working(_, _) =>
            logger.info(s"[${requestId.show}] ROR ${name.show} engine (id=${oldWorkingEngine.engineWithSetting.settings.hashString().show}) will be replaced with engine (id=${newEngineWithSettings.settings.hashString().show}) ...")
            stopEarly(oldWorkingEngine)
            workingStateFrom(newEngineWithSettings)
          case EngineState.Stopped =>
            logger.warn(s"[${requestId.show}] ROR ${name.show} engine (id=${newEngineWithSettings.settings.hashString().show}) cannot be used because the ROR is already stopped!")
            newEngineWithSettings.engine.shutdown()
            EngineState.Stopped
        }
        oldEngineState match {
          case _: EngineState.NotStartedYet => Right(())
          case EngineState.Working(_, _) => Right(())
          case EngineState.Stopped => Left(RawSettingsReloadError.RorInstanceStopped)
        }
      }
    }
  }

  private def workingStateFrom(engineWithSetting: EngineWithSettings)
                              (implicit requestId: RequestId) =
    EngineState.Working(
      engineWithSetting,
      engineWithSetting.expiration.map(_.ttl).map { ttl =>
        scheduler.scheduleOnce(ttl.value) {
          stopEngine(engineWithSetting)
        }
      }
    )

  private def stopEngine(engineWithSetting: EngineWithSettings)
                        (implicit requestId: RequestId): Unit = {
    logger.info(s"[${requestId.show}] ROR ${name.show} engine (id=${engineWithSetting.settings.hashString().show}) is being stopped after TTL were reached ...")
    stop(engineWithSetting)
    currentEngine.transform {
      case EngineState.NotStartedYet(_, _) =>
        EngineState.NotStartedYet(
          recentSettings = Some(engineWithSetting.settings),
          recentExpiration = engineWithSetting.expiration
        )
      case EngineState.Working(_, _) =>
        EngineState.NotStartedYet(
          recentSettings = Some(engineWithSetting.settings),
          recentExpiration = engineWithSetting.expiration
        )
      case EngineState.Stopped =>
        EngineState.Stopped
    }
  }

  private def stateFromInitial(engineWithSetting: EngineWithSettings)
                              (implicit requestId: RequestId): EngineState = {
    engineWithSetting.expiration match {
      case Some(expiration) =>
        isStillValid(expiration.validTo) match {
          case RemainingEngineTime.Valid(remainingEngineTtl) =>
            EngineState.Working(
              engineWithSetting,
              scheduler
                .scheduleOnce(remainingEngineTtl.value) {
                  stopEngine(engineWithSetting)
                }
                .some
            )
          case RemainingEngineTime.Expired =>
            stop(engineWithSetting)
            EngineState.NotStartedYet(
              recentSettings = Some(engineWithSetting.settings),
              recentExpiration = engineWithSetting.expiration
            )
        }
      case None =>
        EngineState.Working(engineWithSetting, scheduledShutdownJob = None)
    }
  }

  private def updateEngineExpiration(expiration: UpdatedExpiration)
                                    (implicit requestId: RequestId): EitherT[Task, RawSettingsReloadError, ReloadResult] = {
    EitherT {
      Task.delay {
        val newEngineState = currentEngine.transformAndGet {
          case EngineState.NotStartedYet(recentSettings, recentExpiration) =>
            EngineState.NotStartedYet(recentSettings, recentExpiration)
          case EngineState.Working(engineWithSetting, scheduledShutdownJob) =>
            logger.info(s"[${requestId.show}] ROR ${name.show} engine (id=${engineWithSetting.settings.hashString().show}) is being updated with new TTL ...")
            scheduledShutdownJob.foreach(_.cancel())
            val engineWithNewExpiration = engineWithSetting.copy(expiration = Some(engineExpiration(expiration)))
            workingStateFrom(engineWithNewExpiration)
          case EngineState.Stopped =>
            EngineState.Stopped
        }
        newEngineState match {
          case _: EngineState.NotStartedYet =>
            Left(RawSettingsReloadError.ReloadingFailed(ReadonlyRest.StartingFailure("Cannot update engine TTL because engine was invalidated")))
          case EngineState.Working(engineWithSetting, _) =>
            Right(ReloadResult(engineWithSetting.engine, engineWithSetting.expiration.get))
          case EngineState.Stopped =>
            Left(RawSettingsReloadError.RorInstanceStopped)
        }
      }
    }
  }

  private def stopEarly(engineState: EngineState.Working)
                       (implicit requestId: RequestId): Unit = {
    engineState.scheduledShutdownJob.foreach(_.cancel())
    scheduler.scheduleOnce(delayOfOldEngineShutdown) {
      logger.info(s"[${requestId.show}] ROR ${name.show} engine (id=${engineState.engineWithSetting.settings.hashString().show}) is being stopped early ...")
      stop(engineState.engineWithSetting)
    }
  }

  private def stopNow(engineState: EngineState.Working)
                     (implicit requestId: RequestId): Unit = {
    logger.info(s"[${requestId.show}] ROR ${name.show} engine (id=${engineState.engineWithSetting.settings.hashString().show}) is being stopped now ...")
    engineState.scheduledShutdownJob.foreach(_.cancel())
    stop(engineState.engineWithSetting)
  }

  private def stop(engineWithSetting: EngineWithSettings)
                  (implicit requestId: RequestId): Unit = {
    engineWithSetting.engine.shutdown()
    logger.info(s"[${requestId.show}] ROR ${name.show} engine (id=${engineWithSetting.settings.hashString().show}) stopped!")
  }

  private def isStillValid(validTo: Instant) = {
    validTo.minusMillis(systemContext.clock.instant().toEpochMilli)
      .toEpochMilli.millis
      .toRefinedPositive
      .map(RemainingEngineTime.Valid.apply)
      .getOrElse(RemainingEngineTime.Expired)
  }
}

object BaseReloadableEngine {

  private[engines] sealed trait InitialEngine

  private[engines] object InitialEngine {
    case object NotConfigured extends InitialEngine
    final case class Configured(engine: Engine,
                                settings: RawRorSettings,
                                expiration: Option[EngineExpiration]) extends InitialEngine
    final case class Invalidated(settings: RawRorSettings,
                                 expiration: EngineExpiration) extends InitialEngine
  }

  private[engines] final case class ReloadResult(engine: Engine,
                                                 expiration: EngineExpiration)

  private[engines] final case class InvalidationResult(settings: RawRorSettings,
                                                       expiration: EngineExpiration)

  private[engines] final case class EngineExpiration(ttl: PositiveFiniteDuration,
                                                     validTo: Instant)

  private[engines] final case class EngineWithSettings(engine: Engine,
                                                       settings: RawRorSettings,
                                                       expiration: Option[EngineExpiration])

  private[engines] sealed trait EngineState

  private[engines] object EngineState {
    final case class NotStartedYet(recentSettings: Option[RawRorSettings],
                                   recentExpiration: Option[EngineExpiration])
      extends EngineState

    final case class Working(engineWithSetting: EngineWithSettings,
                             scheduledShutdownJob: Option[Cancelable])
      extends EngineState

    case object Stopped
      extends EngineState
  }

  private[BaseReloadableEngine] sealed trait UpdatedExpiration
  private[BaseReloadableEngine] object UpdatedExpiration {
    final case class ByTtl(finiteDuration: PositiveFiniteDuration) extends UpdatedExpiration
    final case class ToTime(validTo: Instant,
                            configuredTtl: PositiveFiniteDuration) extends UpdatedExpiration
  }

  private[BaseReloadableEngine] sealed trait RemainingEngineTime
  private[BaseReloadableEngine] object RemainingEngineTime {
    final case class Valid(remainingEngineTtl: PositiveFiniteDuration) extends RemainingEngineTime
    object Expired extends RemainingEngineTime
  }

  private[BaseReloadableEngine] sealed trait EngineUpdateType
  private[BaseReloadableEngine] object EngineUpdateType {
    case object UpdateSettings extends EngineUpdateType
    case object UpdateSettingsTtl extends EngineUpdateType
    final case class SettingsAndTtlUpToDate(engine: Engine, expiration: EngineExpiration) extends EngineUpdateType
  }

  private[BaseReloadableEngine] val delayOfOldEngineShutdown = 10 seconds
}