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
import cats.implicits._
import monix.catnap.Semaphore
import monix.eval.Task
import monix.execution.atomic.{Atomic, AtomicAny}
import monix.execution.{Cancelable, Scheduler}
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.domain.RorConfigurationIndex
import tech.beshu.ror.boot.ReadonlyRest
import tech.beshu.ror.boot.ReadonlyRest.Engine
import tech.beshu.ror.boot.RorInstance.RawConfigReloadError
import tech.beshu.ror.boot.engines.BaseReloadableEngine.EngineState.NotStartedYet
import tech.beshu.ror.boot.engines.BaseReloadableEngine._
import tech.beshu.ror.boot.engines.ConfigHash._
import tech.beshu.ror.configuration.{EnvironmentConfig, RawRorConfig}
import tech.beshu.ror.utils.DurationOps._

import java.time.Instant
import scala.concurrent.duration._
import scala.language.postfixOps

private[engines] abstract class BaseReloadableEngine(val name: String,
                                                     boot: ReadonlyRest,
                                                     initialEngine: InitialEngine,
                                                     reloadInProgress: Semaphore[Task],
                                                     rorConfigurationIndex: RorConfigurationIndex)
                                                    (implicit environmentConfig: EnvironmentConfig,
                                                     scheduler: Scheduler)
  extends Logging {

  import BaseReloadableEngine.EngineUpdateType

  private val currentEngine: Atomic[EngineState] = AtomicAny[EngineState](
    initialEngine match {
      case InitialEngine.Configured(engine, config, expirationConfig) =>
        logger.info(s"ROR $name engine (id=${config.hashString()}) was initiated (${engine.core.accessControl.description}).")
        stateFromInitial(EngineWithConfig(engine, config, expirationConfig))(RequestId(environmentConfig.uuidProvider.random.toString))
      case InitialEngine.NotConfigured =>
        EngineState.NotStartedYet(recentConfig = None, recentExpirationConfig = None)
      case InitialEngine.Invalidated(config, expirationConfig) =>
        EngineState.NotStartedYet(recentConfig = Some(config), recentExpirationConfig = Some(expirationConfig))
    }
  )

  def engine: Option[Engine] = currentEngine.get() match {
    case EngineState.NotStartedYet(_, _) => None
    case EngineState.Working(engineWithConfig, _) => Some(engineWithConfig.engine)
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
            case working@EngineState.Working(engineWithConfig, _) =>
              logger.info(s"[${requestId.show}] ROR $name engine (id=${engineWithConfig.config.hashString()}) will be stopped ...")
              stopNow(working)
            case EngineState.Stopped =>
          }
        }
      } yield ()
    }
  }

  protected def invalidate(keepPreviousConfiguration: Boolean)
                          (implicit requestId: RequestId): Task[Option[InvalidationResult]] = {
    Task.delay {
      val invalidationTimestamp = environmentConfig.clock.instant()
      val previous = currentEngine.getAndTransform {
        case notStarted: EngineState.NotStartedYet =>
          if (keepPreviousConfiguration) {
            notStarted
          } else  {
            EngineState.NotStartedYet(recentConfig = None, recentExpirationConfig = None)
          }
        case oldWorkingEngine@EngineState.Working(engineWithConfig, _) =>
          logger.info(s"[${requestId.show}] ROR $name engine (id=${engineWithConfig.config.hashString()}) will be invalidated ...")
          stopEarly(oldWorkingEngine)
          if (keepPreviousConfiguration) {
            EngineState.NotStartedYet(
              recentConfig = Some(engineWithConfig.config),
              recentExpirationConfig = engineWithConfig.expirationConfig.map {
                recentConfig => recentConfig.copy(validTo = invalidationTimestamp)
              }
            )
          } else {
            EngineState.NotStartedYet(recentConfig = None, recentExpirationConfig = None)
          }
        case EngineState.Stopped =>
          EngineState.Stopped
      }

      previous match {
        case _: EngineState.NotStartedYet => None
        case EngineState.Working(engineWithConfig, _) =>
          engineWithConfig.expirationConfig.map(expirationConfig =>
            InvalidationResult(engineWithConfig.config, expirationConfig.copy(validTo = invalidationTimestamp))
          )
        case EngineState.Stopped => None
      }
    }
  }

  protected final def currentEngineState: EngineState = currentEngine.get()

  protected def reloadEngine(newConfig: RawRorConfig)
                            (implicit requestId: RequestId): EitherT[Task, RawConfigReloadError, Unit] = {
    reloadEngineWithoutTtl(newConfig)
  }

  protected def reloadEngine(newConfig: RawRorConfig,
                             newConfigEngineTtl: PositiveFiniteDuration)
                            (implicit requestId: RequestId): EitherT[Task, RawConfigReloadError, ReloadResult] = {
    reloadEngineWithConfiguredTtl(newConfig, UpdatedConfigExpiration.ByTtl(newConfigEngineTtl))
  }

  protected def reloadEngine(newConfig: RawRorConfig,
                             newConfigExpirationTime: Instant,
                             configuredTtl: PositiveFiniteDuration)
                            (implicit requestId: RequestId): EitherT[Task, RawConfigReloadError, Unit] = {
    isStillValid(newConfigExpirationTime) match {
      case RemainingEngineTime.Valid(_) =>
        reloadEngineWithConfiguredTtl(newConfig, UpdatedConfigExpiration.ToTime(newConfigExpirationTime, configuredTtl))
          .map(_ => ())
      case RemainingEngineTime.Expired =>
        EitherT.right {
          Task.delay {
            val newExpirationConfig = EngineExpirationConfig(ttl = configuredTtl, validTo = newConfigExpirationTime)
            currentEngine.transform {
              case _: EngineState.NotStartedYet =>
                NotStartedYet(recentConfig = Some(newConfig), recentExpirationConfig = Some(newExpirationConfig))
              case working: EngineState.Working =>
                logger.info(s"[${requestId.show}] ROR $name engine (id=${working.engineWithConfig.config.hashString()}) will be invalidated ...")
                stopEarly(working)
                NotStartedYet(recentConfig = Some(newConfig), recentExpirationConfig = Some(newExpirationConfig))
              case EngineState.Stopped =>
                EngineState.Stopped
            }
          }
        }
    }
  }

  private def reloadEngineWithConfiguredTtl(newConfig: RawRorConfig,
                                            expiration: UpdatedConfigExpiration)
                                           (implicit requestId: RequestId): EitherT[Task, RawConfigReloadError, ReloadResult] = {
    for {
      engineUpdateType <- checkUpdateType(newConfig, expiration)
      expirationConfig <- engineUpdateType match {
        case EngineUpdateType.UpdateConfig =>
          runReload(newConfig, Some(expiration)).map { engine =>
            ReloadResult(engine.engine, engine.expirationConfig.get)
          }
        case EngineUpdateType.UpdateConfigTtl =>
          updateEngineExpirationConfig(expiration)
        case EngineUpdateType.ConfigAndTtlUpToDate(engine, expirationConfig) =>
          EitherT.right[RawConfigReloadError](Task.now(ReloadResult(engine, expirationConfig)))
      }
    } yield expirationConfig
  }

  private def reloadEngineWithoutTtl(newConfig: RawRorConfig)
                                    (implicit requestId: RequestId): EitherT[Task, RawConfigReloadError, Unit] = {
    for {
      _ <- canBeReloaded(newConfig)
      _ <- runReload(newConfig, None)
    } yield ()
  }

  private def runReload(newConfig: RawRorConfig,
                        configExpiration: Option[UpdatedConfigExpiration])
                       (implicit requestId: RequestId): EitherT[Task, RawConfigReloadError, EngineWithConfig] = {
    for {
      newEngineWithConfig <- reloadWith(newConfig, configExpiration)
      _ <- replaceCurrentEngine(newEngineWithConfig)
    } yield newEngineWithConfig
  }

  private def checkUpdateType(newConfig: RawRorConfig,
                              newConfigExpiration: UpdatedConfigExpiration): EitherT[Task, RawConfigReloadError, EngineUpdateType] = {
    EitherT {
      Task.delay {
        currentEngine.get() match {
          case EngineState.NotStartedYet(_, _) =>
            Right(EngineUpdateType.UpdateConfig)
          case EngineState.Working(EngineWithConfig(_, currentConfig, _), _) if currentConfig != newConfig =>
            Right(EngineUpdateType.UpdateConfig)
          case EngineState.Working(EngineWithConfig(engine, _, engineExpirationConfig), _) =>
            checkIfExpirationConfigHasChanged(engine, newConfigExpiration, engineExpirationConfig)
          case EngineState.Stopped =>
            Left(RawConfigReloadError.RorInstanceStopped)
        }
      }
    }
  }

  private def checkIfExpirationConfigHasChanged(engine: Engine,
                                                newConfigExpiration: UpdatedConfigExpiration,
                                                engineExpirationConfig: Option[EngineExpirationConfig]) = {
    newConfigExpiration match {
      case UpdatedConfigExpiration.ByTtl(_) =>
        Right(EngineUpdateType.UpdateConfigTtl)
      case UpdatedConfigExpiration.ToTime(validTo, configuredTtl) =>
        val providedExpirationConfig = EngineExpirationConfig(configuredTtl, validTo)
        if (engineExpirationConfig.contains(providedExpirationConfig)) {
          Right(EngineUpdateType.ConfigAndTtlUpToDate(engine, providedExpirationConfig))
        } else {
          Right(EngineUpdateType.UpdateConfigTtl)
        }
    }
  }

  private def canBeReloaded(newConfig: RawRorConfig): EitherT[Task, RawConfigReloadError, Unit] = {
    EitherT {
      Task.delay {
        currentEngine.get() match {
          case EngineState.NotStartedYet(_, _) =>
            Right(())
          case EngineState.Working(EngineWithConfig(_, currentConfig, _), _) if currentConfig != newConfig =>
            Right(())
          case EngineState.Working(EngineWithConfig(_, currentConfig, _), _) =>
            Left(RawConfigReloadError.ConfigUpToDate(currentConfig))
          case EngineState.Stopped =>
            Left(RawConfigReloadError.RorInstanceStopped)
        }
      }
    }
  }

  private def reloadWith(newConfig: RawRorConfig,
                         configExpiration: Option[UpdatedConfigExpiration]): EitherT[Task, RawConfigReloadError, EngineWithConfig] = EitherT {
    tryToLoadRorCore(newConfig)
      .map(_
        .map { engine =>
          EngineWithConfig(
            engine = engine,
            config = newConfig,
            expirationConfig = configExpiration.map(engineExpirationConfig)
          )
        }
        .leftMap(RawConfigReloadError.ReloadingFailed.apply)
      )
  }

  private def engineExpirationConfig(configExpiration: UpdatedConfigExpiration) = {
    configExpiration match {
      case UpdatedConfigExpiration.ByTtl(ttl) =>
        EngineExpirationConfig(ttl = ttl, validTo = environmentConfig.clock.instant().plusMillis(ttl.value.toMillis))
      case UpdatedConfigExpiration.ToTime(validTo, configuredTtl) =>
        EngineExpirationConfig(ttl = configuredTtl, validTo = validTo)
    }
  }

  private def tryToLoadRorCore(config: RawRorConfig) =
    boot.loadRorCore(config, rorConfigurationIndex)

  private def replaceCurrentEngine(newEngineWithConfig: EngineWithConfig)
                                  (implicit requestId: RequestId): EitherT[Task, RawConfigReloadError, Unit] = {
    EitherT {
      Task.delay {
        val oldEngineState = currentEngine.getAndTransform {
          case _: EngineState.NotStartedYet =>
            logger.info(s"[${requestId.show}] ROR $name engine (id=${newEngineWithConfig.config.hashString()}) is going to be used ...")
            workingStateFrom(newEngineWithConfig)
          case oldWorkingEngine@EngineState.Working(_, _) =>
            logger.info(s"[${requestId.show}] ROR $name engine (id=${oldWorkingEngine.engineWithConfig.config.hashString()}) will be replaced with engine (id=${newEngineWithConfig.config.hashString()}) ...")
            stopEarly(oldWorkingEngine)
            workingStateFrom(newEngineWithConfig)
          case EngineState.Stopped =>
            logger.warn(s"[${requestId.show}] ROR $name engine (id=${newEngineWithConfig.config.hashString()}) cannot be used because the ROR is already stopped!")
            newEngineWithConfig.engine.shutdown()
            EngineState.Stopped
        }
        oldEngineState match {
          case _: EngineState.NotStartedYet => Right(())
          case EngineState.Working(_, _) => Right(())
          case EngineState.Stopped => Left(RawConfigReloadError.RorInstanceStopped)
        }
      }
    }
  }

  private def workingStateFrom(engineWithConfig: EngineWithConfig)
                              (implicit requestId: RequestId) =
    EngineState.Working(
      engineWithConfig,
      engineWithConfig.expirationConfig.map(_.ttl).map { ttl =>
        scheduler.scheduleOnce(ttl.value) {
          stopEngine(engineWithConfig)
        }
      }
    )

  private def stopEngine(engineWithConfig: EngineWithConfig)
                        (implicit requestId: RequestId): Unit = {
    logger.info(s"[${requestId.show}] ROR $name engine (id=${engineWithConfig.config.hashString()}) is being stopped after TTL were reached ...")
    stop(engineWithConfig)
    currentEngine.transform {
      case EngineState.NotStartedYet(_, _) =>
        EngineState.NotStartedYet(
          recentConfig = Some(engineWithConfig.config),
          recentExpirationConfig = engineWithConfig.expirationConfig
        )
      case EngineState.Working(_, _) =>
        EngineState.NotStartedYet(
          recentConfig = Some(engineWithConfig.config),
          recentExpirationConfig = engineWithConfig.expirationConfig
        )
      case EngineState.Stopped =>
        EngineState.Stopped
    }
  }

  private def stateFromInitial(engineWithConfig: EngineWithConfig)
                              (implicit requestId: RequestId): EngineState = {
    engineWithConfig.expirationConfig match {
      case Some(expirationConfig) =>
        isStillValid(expirationConfig.validTo) match {
          case RemainingEngineTime.Valid(remainingEngineTtl) =>
            EngineState.Working(
              engineWithConfig,
              scheduler
                .scheduleOnce(remainingEngineTtl.value) {
                  stopEngine(engineWithConfig)
                }
                .some
            )
          case RemainingEngineTime.Expired =>
            stop(engineWithConfig)
            EngineState.NotStartedYet(
              recentConfig = Some(engineWithConfig.config),
              recentExpirationConfig = engineWithConfig.expirationConfig
            )
        }
      case None =>
        EngineState.Working(engineWithConfig, scheduledShutdownJob = None)
    }
  }

  private def updateEngineExpirationConfig(configExpiration: UpdatedConfigExpiration)
                                          (implicit requestId: RequestId): EitherT[Task, RawConfigReloadError, ReloadResult] = {
    EitherT {
      Task.delay {
        val newEngineState = currentEngine.transformAndGet {
          case EngineState.NotStartedYet(recentConfig, recentExpirationConfig) =>
            EngineState.NotStartedYet(recentConfig, recentExpirationConfig)
          case EngineState.Working(engineWithConfig, scheduledShutdownJob) =>
            logger.info(s"[${requestId.show}] ROR $name engine (id=${engineWithConfig.config.hashString()}) is being updated with new TTL ...")
            scheduledShutdownJob.foreach(_.cancel())
            val engineWithNewExpirationConfig = engineWithConfig.copy(expirationConfig = Some(engineExpirationConfig(configExpiration)))
            workingStateFrom(engineWithNewExpirationConfig)
          case EngineState.Stopped =>
            EngineState.Stopped
        }
        newEngineState match {
          case _: EngineState.NotStartedYet =>
            Left(RawConfigReloadError.ReloadingFailed(ReadonlyRest.StartingFailure("Cannot update engine TTL because engine was invalidated")))
          case EngineState.Working(engineWithConfig, _) =>
            Right(ReloadResult(engineWithConfig.engine, engineWithConfig.expirationConfig.get))
          case EngineState.Stopped =>
            Left(RawConfigReloadError.RorInstanceStopped)
        }
      }
    }
  }

  private def stopEarly(engineState: EngineState.Working)
                       (implicit requestId: RequestId): Unit = {
    engineState.scheduledShutdownJob.foreach(_.cancel())
    scheduler.scheduleOnce(BaseReloadableEngine.delayOfOldEngineShutdown) {
      logger.info(s"[${requestId.show}] ROR $name engine (id=${engineState.engineWithConfig.config.hashString()}) is being stopped early ...")
      stop(engineState.engineWithConfig)
    }
  }

  private def stopNow(engineState: EngineState.Working)
                     (implicit requestId: RequestId): Unit = {
    logger.info(s"[${requestId.show}] ROR $name engine (id=${engineState.engineWithConfig.config.hashString()}) is being stopped now ...")
    engineState.scheduledShutdownJob.foreach(_.cancel())
    stop(engineState.engineWithConfig)
  }

  private def stop(engineWithConfig: EngineWithConfig)
                  (implicit requestId: RequestId): Unit = {
    engineWithConfig.engine.shutdown()
    logger.info(s"[${requestId.show}] ROR $name engine (id=${engineWithConfig.config.hashString()}) stopped!")
  }

  private def isStillValid(validTo: Instant) = {
    validTo.minusMillis(environmentConfig.clock.instant().toEpochMilli)
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
                                config: RawRorConfig,
                                expirationConfig: Option[EngineExpirationConfig]) extends InitialEngine
    final case class Invalidated(config: RawRorConfig,
                                 expirationConfig: EngineExpirationConfig) extends InitialEngine
  }

  private[engines] final case class ReloadResult(engine: Engine,
                                                 expirationConfig: EngineExpirationConfig)

  private[engines] final case class InvalidationResult(config: RawRorConfig,
                                                       expirationConfig: EngineExpirationConfig)

  private[engines] final case class EngineExpirationConfig(ttl: PositiveFiniteDuration,
                                                           validTo: Instant)

  private[engines] final case class EngineWithConfig(engine: Engine,
                                                     config: RawRorConfig,
                                                     expirationConfig: Option[EngineExpirationConfig])

  private[engines] sealed trait EngineState

  private[engines] object EngineState {
    final case class NotStartedYet(recentConfig: Option[RawRorConfig],
                                   recentExpirationConfig: Option[EngineExpirationConfig])
      extends EngineState

    final case class Working(engineWithConfig: EngineWithConfig,
                             scheduledShutdownJob: Option[Cancelable])
      extends EngineState

    case object Stopped
      extends EngineState
  }

  private[BaseReloadableEngine] sealed trait UpdatedConfigExpiration
  private[BaseReloadableEngine] object UpdatedConfigExpiration {
    final case class ByTtl(finiteDuration: PositiveFiniteDuration) extends UpdatedConfigExpiration
    final case class ToTime(validTo: Instant,
                            configuredTtl: PositiveFiniteDuration) extends UpdatedConfigExpiration
  }

  private[BaseReloadableEngine] sealed trait RemainingEngineTime
  private[BaseReloadableEngine] object RemainingEngineTime {
    final case class Valid(remainingEngineTtl: PositiveFiniteDuration) extends RemainingEngineTime
    object Expired extends RemainingEngineTime
  }

  private[BaseReloadableEngine] sealed trait EngineUpdateType
  private[BaseReloadableEngine] object EngineUpdateType {
    case object UpdateConfig extends EngineUpdateType
    case object UpdateConfigTtl extends EngineUpdateType
    final case class ConfigAndTtlUpToDate(engine: Engine, expirationConfig: EngineExpirationConfig) extends EngineUpdateType
  }

  private[BaseReloadableEngine] val delayOfOldEngineShutdown = 10 seconds
}