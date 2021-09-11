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
import monix.execution.atomic.Atomic
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.domain.RorConfigurationIndex
import tech.beshu.ror.boot.RorInstance.RawConfigReloadError
import tech.beshu.ror.boot.engines.BaseReloadableEngine.delayOfOldEngineShutdown
import tech.beshu.ror.boot.{Engine, ReadonlyRest}
import tech.beshu.ror.configuration.RawRorConfig
import tech.beshu.ror.es.AuditSinkService

import scala.concurrent.duration._
import scala.language.postfixOps

private[engines] class BaseReloadableEngine(name: String,
                                            boot: ReadonlyRest,
                                            initialEngine: Option[(Engine, RawRorConfig)],
                                            reloadInProgress: Semaphore[Task],
                                            rorConfigurationIndex: RorConfigurationIndex,
                                            auditSink: AuditSinkService)
                                           (implicit scheduler: Scheduler)
  extends Logging {

  private val currentEngine = Atomic(initialEngine)

  def engine: Option[Engine] = currentEngine.get().map(_._1)

  def stop(): Task[Unit] = {
    reloadInProgress.withPermit {
      for {
        optEngine <- Task.delay(currentEngine.getAndSet(None))
        _ <- Task.delay(optEngine.foreach { case (engine, _) => engine.shutdown() })
        _ <- Task.delay(logger.info(s"ROR $name engine stopped!"))
      } yield ()
    }
  }

  protected def reloadEngine(newConfig: RawRorConfig,
                             ttl: Option[FiniteDuration] = None): EitherT[Task, RawConfigReloadError, Unit] = {
    for {
      _ <- shouldBeReloaded(newConfig)
      newEngine <- reloadWith(newConfig)
      oldEngine <- replaceCurrentEngine(newEngine, newConfig)
      _ <- scheduleDelayedShutdown(oldEngine)
      _ <- ttl match {
        case Some(t) => scheduleDelayedShutdown(newEngine, t)
        case None => EitherT.pure[Task, RawConfigReloadError](())
      }
    } yield ()
  }

  private def shouldBeReloaded(config: RawRorConfig): EitherT[Task, RawConfigReloadError, Unit] = {
    currentEngine.get() match {
      case Some((_, currentConfig)) =>
        EitherT.cond[Task](
          currentConfig != config,
          (),
          RawConfigReloadError.ConfigUpToDate
        )
      case None =>
        EitherT.leftT[Task, Unit](RawConfigReloadError.RorInstanceStopped)
    }
  }

  private def reloadWith(config: RawRorConfig): EitherT[Task, RawConfigReloadError, Engine] = EitherT {
    tryToLoadRorCore(config)
      .map(_.leftMap(RawConfigReloadError.ReloadingFailed.apply))
  }

  private def tryToLoadRorCore(config: RawRorConfig) =
    boot.loadRorCore(config, rorConfigurationIndex, auditSink)

  private def replaceCurrentEngine(newEngine: Engine,
                                   newEngineConfig: RawRorConfig): EitherT[Task, RawConfigReloadError, Engine] = {
    currentEngine
      .getAndTransform {
        _.map(_ => (newEngine, newEngineConfig))
      } match {
      case Some((engine, _)) => EitherT.rightT[Task, RawConfigReloadError](engine)
      case None => EitherT.leftT[Task, Engine](RawConfigReloadError.RorInstanceStopped)
    }
  }

  private def scheduleDelayedShutdown(engine: Engine,
                                      ttl: FiniteDuration = delayOfOldEngineShutdown) = {
    EitherT.right[RawConfigReloadError](Task.delay {
      scheduler.scheduleOnce(ttl) {
        stop()
      }
    })
  }
}

object BaseReloadableEngine {
  private[BaseReloadableEngine] val delayOfOldEngineShutdown = 10 seconds
}