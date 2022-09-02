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
import tech.beshu.ror.boot.engines.BaseReloadableEngine.{EngineExpirationConfig, EngineState, EngineWithConfig}
import tech.beshu.ror.boot.engines.ConfigHash._
import tech.beshu.ror.configuration.RawRorConfig
import java.time.{Clock, Instant}

import scala.concurrent.duration._
import scala.language.postfixOps

private[engines] abstract class BaseReloadableEngine(val name: String,
                                                     boot: ReadonlyRest,
                                                     initialEngine: Option[(Engine, RawRorConfig)],
                                                     reloadInProgress: Semaphore[Task],
                                                     rorConfigurationIndex: RorConfigurationIndex)
                                                    (implicit scheduler: Scheduler,
                                                     clock: Clock)
  extends Logging {

  import BaseReloadableEngine.ConfigUpdate

  private val currentEngine: Atomic[EngineState] = AtomicAny[EngineState](
    initialEngine match {
      case Some((engine, config)) =>
        logger.info(s"ROR $name engine (id=${config.hashString()}) was initiated (${engine.core.accessControl.description}).")
        EngineState.Working(EngineWithConfig(engine, config, expirationConfig = None), scheduledShutdownJob = None)
      case None =>
        EngineState.NotStartedYet(recentConfig = None, recentExpirationConfig = None)
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

  def invalidate()
                (implicit requestId: RequestId): Task[Unit] = {
    reloadInProgress.withPermit {
      Task.delay {
        currentEngine.transform {
          case notStarted: EngineState.NotStartedYet =>
            notStarted
          case oldWorkingEngine@EngineState.Working(engineWithConfig, _) =>
            logger.info(s"[${requestId.show}] ROR $name engine (id=${engineWithConfig.config.hashString()}) will be invalidated ... ")
            stopEarly(oldWorkingEngine)
            EngineState.NotStartedYet(recentConfig = Some(engineWithConfig.config), recentExpirationConfig = engineWithConfig.expirationConfig)
          case EngineState.Stopped =>
            EngineState.Stopped
        }
      }
    }
  }

  protected final def currentEngineState: EngineState = currentEngine.get()

  protected def reloadEngine(newConfig: RawRorConfig,
                             newConfigEngineTtl: Option[FiniteDuration] = None)
                            (implicit requestId: RequestId): EitherT[Task, RawConfigReloadError, Unit] = {
    newConfigEngineTtl match {
      case Some(ttl) =>
        reloadEngineWithConfiguredTtl(newConfig, ttl)
      case None =>
        reloadEngineWithoutTtl(newConfig)
    }
  }

  private def reloadEngineWithConfiguredTtl(newConfig: RawRorConfig,
                                            ttl: FiniteDuration)
                                           (implicit requestId: RequestId): EitherT[Task, RawConfigReloadError, Unit] = {
    for {
      configUpdate <- checkConfigUpdateType(newConfig)
      _ <- configUpdate match {
        case ConfigUpdate.NewConfig =>
          runReload(newConfig, Some(ttl))
        case ConfigUpdate.ConfigUpToDate =>
          updateEngineExpirationConfig(ttl)
      }
    } yield ()
  }

  private def reloadEngineWithoutTtl(newConfig: RawRorConfig)
                                    (implicit requestId: RequestId): EitherT[Task, RawConfigReloadError, Unit] = {
    for {
      _ <- canBeReloaded(newConfig)
      _ <- runReload(newConfig, None)
    } yield ()
  }

  private def runReload(newConfig: RawRorConfig,
                        newConfigEngineTtl: Option[FiniteDuration])
                       (implicit requestId: RequestId): EitherT[Task, RawConfigReloadError, Unit] = {
    for {
      newEngineWithConfig <- reloadWith(newConfig, newConfigEngineTtl)
      _ <- replaceCurrentEngine(newEngineWithConfig)
    } yield ()
  }

  private def checkConfigUpdateType(newConfig: RawRorConfig): EitherT[Task, RawConfigReloadError, ConfigUpdate] = {
    EitherT {
      Task.delay {
        currentEngine.get() match {
          case EngineState.NotStartedYet(_, _) =>
            Right(ConfigUpdate.NewConfig)
          case EngineState.Working(EngineWithConfig(_, currentConfig, _), _) if currentConfig != newConfig =>
            Right(ConfigUpdate.NewConfig)
          case EngineState.Working(EngineWithConfig(_, currentConfig, _), _) =>
            Right(ConfigUpdate.ConfigUpToDate)
          case EngineState.Stopped =>
            Left(RawConfigReloadError.RorInstanceStopped)
        }
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
                         newConfigEngineTtl: Option[FiniteDuration]): EitherT[Task, RawConfigReloadError, EngineWithConfig] = EitherT {
    tryToLoadRorCore(newConfig)
      .map(_
        .map { engine =>
          EngineWithConfig(
            engine = engine,
            config = newConfig,
            expirationConfig = newConfigEngineTtl.map(newExpirationConfig)
          )
        }
        .leftMap(RawConfigReloadError.ReloadingFailed.apply)
      )
  }

  private def newExpirationConfig(ttl: FiniteDuration) = {
    EngineExpirationConfig(ttl = ttl, validTo = clock.instant().plusMillis(ttl.toMillis))
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
        scheduler.scheduleOnce(ttl) {
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
      }
    )

  private def updateEngineExpirationConfig(ttl: FiniteDuration)
                                          (implicit requestId: RequestId): EitherT[Task, RawConfigReloadError, Unit] = {
    EitherT {
      Task.delay {
        val oldEngineState = currentEngine.getAndTransform {
          case EngineState.NotStartedYet(recentConfig, recentExpirationConfig) =>
            EngineState.NotStartedYet(recentConfig, recentExpirationConfig)
          case EngineState.Working(engineWithConfig, scheduledShutdownJob) =>
            logger.info(s"[${requestId.show}] ROR $name engine (id=${engineWithConfig.config.hashString()}) is being updated with new TTL ...")
            scheduledShutdownJob.foreach(_.cancel())
            val engineWithNewExpirationConfig = engineWithConfig.copy(expirationConfig = Some(newExpirationConfig(ttl)))
            workingStateFrom(engineWithNewExpirationConfig)
          case EngineState.Stopped =>
            EngineState.Stopped
        }
        oldEngineState match {
          case _: EngineState.NotStartedYet =>
            Left(RawConfigReloadError.ReloadingFailed(ReadonlyRest.StartingFailure("Cannot update engine TTL because engine was invalidated")))
          case EngineState.Working(_, _) =>
            Right(())
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
}

object BaseReloadableEngine {

  private[engines] case class EngineWithConfig(engine: Engine,
                                               config: RawRorConfig,
                                               expirationConfig: Option[EngineExpirationConfig])

  private[engines] final case class EngineExpirationConfig(ttl: FiniteDuration,
                                                           validTo: Instant)

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

  private[BaseReloadableEngine] sealed trait ConfigUpdate
  private[BaseReloadableEngine] object ConfigUpdate {
    case object NewConfig extends ConfigUpdate
    case object ConfigUpToDate extends ConfigUpdate
  }

  private[BaseReloadableEngine] val delayOfOldEngineShutdown = 10 seconds
}