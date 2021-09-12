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
import monix.execution.Scheduler
import monix.execution.atomic.{Atomic, AtomicAny}
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.domain.RorConfigurationIndex
import tech.beshu.ror.boot.RorInstance.RawConfigReloadError
import tech.beshu.ror.boot.engines.BaseReloadableEngine.{EngineState, delayOfOldEngineShutdown}
import tech.beshu.ror.boot.engines.ConfigHash._
import tech.beshu.ror.boot.{Engine, ReadonlyRest}
import tech.beshu.ror.configuration.RawRorConfig
import tech.beshu.ror.es.AuditSinkService

import scala.concurrent.duration._
import scala.language.postfixOps

private[engines] abstract class BaseReloadableEngine(name: String,
                                                     boot: ReadonlyRest,
                                                     initialEngine: Option[(Engine, RawRorConfig)],
                                                     reloadInProgress: Semaphore[Task],
                                                     rorConfigurationIndex: RorConfigurationIndex,
                                                     auditSink: AuditSinkService)
                                                    (implicit scheduler: Scheduler)
  extends Logging {

  private val currentEngine: Atomic[EngineState] = AtomicAny[EngineState](
    initialEngine match {
      case Some((engine, config)) => EngineState.Working(engine, config)
      case None => EngineState.NotStartedYet
    }
  )

  protected def stateAfterStop: EngineState

  def engine: Option[Engine] = currentEngine.get() match {
    case EngineState.NotStartedYet => None
    case EngineState.Working(engine, _) => Some(engine)
    case EngineState.Stopped => None
  }

  def stop(): Task[Unit] = {
    reloadInProgress.withPermit {
      for {
        state <- Task.delay(currentEngine.getAndSet(stateAfterStop))
        _ <- Task.delay {
          state match {
            case EngineState.NotStartedYet =>
            case EngineState.Working(engine, config) =>
              engine.shutdown()
              logger.info(s"[${config.hashString()}] ROR $name engine stopped!")
            case EngineState.Stopped =>
          }
        }
      } yield ()
    }
  }

  protected def reloadEngine(newConfig: RawRorConfig,
                             newConfigEngineTtl: Option[FiniteDuration] = None): EitherT[Task, RawConfigReloadError, Unit] = {
    for {
      _ <- shouldBeReloaded(newConfig)
      newEngine <- reloadWith(newConfig)
      oldEngine <- replaceCurrentEngine(newEngine, newConfig)
      _ <- oldEngine match {
        case Some(engineToStop) => scheduleDelayedShutdown(engineToStop) // it doesn't work atm!!
        case None => EitherT.pure[Task, RawConfigReloadError](())
      }
      _ <- newConfigEngineTtl match {
        case Some(t) => scheduleDelayedShutdown(newEngine, t)
        case None => EitherT.pure[Task, RawConfigReloadError](())
      }
    } yield ()
  }

  private def shouldBeReloaded(newConfig: RawRorConfig): EitherT[Task, RawConfigReloadError, Unit] = {
    currentEngine.get() match {
      case EngineState.NotStartedYet => doReload
      case EngineState.Working(_, currentConfig) if currentConfig != newConfig => doReload
      case EngineState.Working(_, _) => EitherT.leftT[Task, Unit](RawConfigReloadError.ConfigUpToDate)
      case EngineState.Stopped => EitherT.leftT[Task, Unit](RawConfigReloadError.RorInstanceStopped)
    }
  }

  private lazy val doReload = EitherT.pure[Task, RawConfigReloadError](())

  private def reloadWith(config: RawRorConfig): EitherT[Task, RawConfigReloadError, Engine] = EitherT {
    tryToLoadRorCore(config)
      .map(_.leftMap(RawConfigReloadError.ReloadingFailed.apply))
  }

  private def tryToLoadRorCore(config: RawRorConfig) =
    boot.loadRorCore(config, rorConfigurationIndex, auditSink)

  private def replaceCurrentEngine(newEngine: Engine,
                                   newEngineConfig: RawRorConfig): EitherT[Task, RawConfigReloadError, Option[Engine]] = {
    val result = currentEngine.getAndTransform {
      case EngineState.NotStartedYet => EngineState.Working(newEngine, newEngineConfig)
      case EngineState.Working(_, _) => EngineState.Working(newEngine, newEngineConfig)
      case EngineState.Stopped => EngineState.Stopped
    }
    result match {
      case EngineState.NotStartedYet => EitherT.rightT[Task, RawConfigReloadError](None)
      case EngineState.Working(oldEngine, _) => EitherT.rightT[Task, RawConfigReloadError](Some(oldEngine))
      case EngineState.Stopped => EitherT.leftT[Task, Option[Engine]](RawConfigReloadError.RorInstanceStopped)
    }
  }

  private def scheduleDelayedShutdown(engine: Engine,
                                      ttl: FiniteDuration = delayOfOldEngineShutdown) = {
    EitherT.right[RawConfigReloadError](Task.delay {
      scheduler.scheduleOnce(ttl) {
        stop().runAsync {
          case Right(_) =>
          case Left(ex) =>
            logger.error(s"ROR cannot stop $name engine!", ex)
        }
      }
    })
  }
}

object BaseReloadableEngine {
  private[engines] sealed trait EngineState
  private[engines] object EngineState {
    case object NotStartedYet
      extends EngineState
    final case class Working(engine: Engine,
                             config: RawRorConfig)
      extends EngineState
    case object Stopped
      extends EngineState
  }

  private[BaseReloadableEngine] val delayOfOldEngineShutdown = 10 seconds
}