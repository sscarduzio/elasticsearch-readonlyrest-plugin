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
import tech.beshu.ror.boot.engines.BaseReloadableEngine.{EngineState, EngineWithConfig}
import tech.beshu.ror.boot.engines.ConfigHash._
import tech.beshu.ror.configuration.RawRorConfig

import scala.concurrent.duration._
import scala.language.postfixOps

private[engines] abstract class BaseReloadableEngine(val name: String,
                                                     boot: ReadonlyRest,
                                                     initialEngine: Option[(Engine, RawRorConfig)],
                                                     reloadInProgress: Semaphore[Task],
                                                     rorConfigurationIndex: RorConfigurationIndex)
                                                    (implicit scheduler: Scheduler)
  extends Logging {

  private val currentEngine: Atomic[EngineState] = AtomicAny[EngineState](
    initialEngine match {
      case Some((engine, config)) =>
        logger.info(s"ROR $name engine (id=${config.hashString()}) was initiated.")
        EngineState.Working(EngineWithConfig(engine, config, ttl = None), scheduledShutdownJob = None)
      case None =>
        EngineState.NotStartedYet
    }
  )

  def engine: Option[Engine] = currentEngine.get() match {
    case EngineState.NotStartedYet => None
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
            case EngineState.NotStartedYet =>
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
          case EngineState.NotStartedYet =>
            EngineState.NotStartedYet
          case oldWorkingEngine@EngineState.Working(engineWithConfig, _) =>
            logger.info(s"[${requestId.show}] ROR $name engine (id=${engineWithConfig.config.hashString()}) will be invalidated ... ")
            stopEarly(oldWorkingEngine)
            EngineState.NotStartedYet
          case EngineState.Stopped =>
            EngineState.Stopped
        }
      }
    }
  }

  protected def reloadEngine(newConfig: RawRorConfig,
                             newConfigEngineTtl: Option[FiniteDuration] = None)
                            (implicit requestId: RequestId): EitherT[Task, RawConfigReloadError, Unit] = {
    for {
      _ <- canBeReloaded(newConfig)
      newEngineWithConfig <- reloadWith(newConfig, newConfigEngineTtl)
      _ <- replaceCurrentEngine(newEngineWithConfig)
    } yield ()
  }

  private def canBeReloaded(newConfig: RawRorConfig): EitherT[Task, RawConfigReloadError, Unit] = {
    EitherT {
      Task.delay {
        currentEngine.get() match {
          case EngineState.NotStartedYet =>
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
        .map(EngineWithConfig(_, newConfig, newConfigEngineTtl))
        .leftMap(RawConfigReloadError.ReloadingFailed.apply)
      )
  }

  private def tryToLoadRorCore(config: RawRorConfig) =
    boot.loadRorCore(config, rorConfigurationIndex)

  private def replaceCurrentEngine(newEngineWithConfig: EngineWithConfig)
                                  (implicit requestId: RequestId): EitherT[Task, RawConfigReloadError, Option[EngineWithConfig]] = {
    EitherT {
      Task.delay {
        val result = currentEngine.getAndTransform {
          case EngineState.NotStartedYet =>
            workingStateFrom(newEngineWithConfig)
          case oldWorkingEngine@EngineState.Working(_, _) =>
            stopEarly(oldWorkingEngine)
            workingStateFrom(newEngineWithConfig)
          case EngineState.Stopped =>
            newEngineWithConfig.engine.shutdown()
            EngineState.Stopped
        }
        result match {
          case EngineState.NotStartedYet => Right(None)
          case EngineState.Working(oldEngineWithConfig, _) => Right(Some(oldEngineWithConfig))
          case EngineState.Stopped => Left(RawConfigReloadError.RorInstanceStopped)
        }
      }
    }
  }

  private def workingStateFrom(engineWithConfig: EngineWithConfig)
                              (implicit requestId: RequestId) =
    EngineState.Working(
      engineWithConfig,
      engineWithConfig.ttl.map { ttl =>
        scheduler.scheduleOnce(ttl) {
          logger.info(s"[${requestId.show}] ROR $name engine (id=${engineWithConfig.config.hashString()}) is being stopped after TTL were reached ...") // todo: debug
          stop(engineWithConfig)
          currentEngine.transform {
            case EngineState.NotStartedYet => EngineState.NotStartedYet
            case EngineState.Working(_, _) => EngineState.NotStartedYet
            case EngineState.Stopped => EngineState.Stopped
          }
        }
      }
    )

  private def stopEarly(engineState: EngineState.Working)
                       (implicit requestId: RequestId): Unit = {
    engineState.scheduledShutdownJob.foreach(_.cancel())
    scheduler.scheduleOnce(BaseReloadableEngine.delayOfOldEngineShutdown) {
      logger.info(s"[${requestId.show}] ROR $name engine (id=${engineState.engineWithConfig.config.hashString()}) is being stopped early ...") // todo: debug
      stop(engineState.engineWithConfig)
    }
  }

  private def stopNow(engineState: EngineState.Working)
                     (implicit requestId: RequestId): Unit = {
    logger.info(s"[${requestId.show}] ROR $name engine (id=${engineState.engineWithConfig.config.hashString()}) is being stopped now ...") // todo: debug
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
                                               ttl: Option[FiniteDuration])

  private[engines] sealed trait EngineState
  private[engines] object EngineState {
    case object NotStartedYet
      extends EngineState
    final case class Working(engineWithConfig: EngineWithConfig,
                             scheduledShutdownJob: Option[Cancelable])
      extends EngineState
    case object Stopped
      extends EngineState
  }

  private[BaseReloadableEngine] val delayOfOldEngineShutdown = 10 seconds
}