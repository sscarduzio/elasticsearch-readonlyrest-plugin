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
package tech.beshu.ror.boot

import cats.Show
import cats.implicits.toShow
import monix.eval.Task
import monix.execution.{Cancelable, Scheduler}
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.SystemContext
import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.boot.RorInstance.ScheduledReloadError.{EngineReloadError, ReloadingInProgress}
import tech.beshu.ror.boot.RorInstance.{IndexSettingsReloadError, RawSettingsReloadError, ScheduledReloadError}
import tech.beshu.ror.utils.DurationOps.PositiveFiniteDuration
import tech.beshu.ror.implicits.*

import java.util.concurrent.atomic.AtomicReference

trait RorSettingsAutoReloader {
  def start(): Unit
  def stop(): Task[Unit]
}

class EnabledRorSettingsAutoReloader(reloadInterval: PositiveFiniteDuration,
                                     instance: RorInstance)
                                    (implicit systemContext: SystemContext,
                                     scheduler: Scheduler)
  extends RorSettingsAutoReloader with Logging {

  private val reloadTaskState: AtomicReference[ReloadTaskState] = new AtomicReference(ReloadTaskState.NotInitiated)

  override def start(): Unit = {
    logger.info(s"[CLUSTERWIDE SETTINGS] Auto reloading of ReadonlyREST in-index settings enabled")
    scheduleEnginesReload(reloadInterval)
  }

  override def stop(): Task[Unit] = {
    for {
      currentState <- Task.delay(reloadTaskState.getAndSet(ReloadTaskState.Stopped))
      _ <- Task.delay(currentState match {
        case ReloadTaskState.NotInitiated => // do nothing
        case ReloadTaskState.Running(cancelable) => cancelable.cancel()
        case ReloadTaskState.Stopped => // do nothing
      })
    } yield ()
  }

  private def scheduleEnginesReload(interval: PositiveFiniteDuration): Unit = {
    val reloadTask = { (requestId: RequestId) =>
      Task.sequence {
        Seq(
          instance.tryMainEngineReload(requestId).map(result => (SettingsType.Main, result)),
          instance.tryTestEngineReload(requestId).map(result => (SettingsType.Test, result))
        )
      }
    }
    scheduleNextIfNotStopping(interval, reloadTask)
  }

  private def scheduleNextIfNotStopping(interval: PositiveFiniteDuration,
                                        reloadTask: RequestId => Task[Seq[(SettingsType, Either[ScheduledReloadError, Unit])]]): Unit = {
    implicit val requestId: RequestId = RequestId(systemContext.uuidProvider.random.toString)
    val nextTask = scheduleIndexSettingsChecking(interval, reloadTask)
    trySetNextReloadTask(nextTask) match {
      case ReloadTaskState.NotInitiated => // nothing to do
      case ReloadTaskState.Running(_) => // nothing to do
      case ReloadTaskState.Stopped => nextTask.cancel()
    }
  }

  private def scheduleIndexSettingsChecking(interval: PositiveFiniteDuration,
                                            reloadTask: RequestId => Task[Seq[(SettingsType, Either[ScheduledReloadError, Unit])]])
                                           (implicit requestId: RequestId): CancelableWithRequestId = {
    logger.debug(s"[CLUSTERWIDE SETTINGS][${requestId.show}] Scheduling next in-index settings check within ${interval.show}")
    val cancellable = scheduler.scheduleOnce(interval.value) {
      logger.debug(s"[CLUSTERWIDE SETTINGS][${requestId.show}] Loading ReadonlyREST settings from index ...")
      reloadTask(requestId)
        .runAsync {
          case Right(reloadResults) =>
            reloadResults.foreach(logSettingsReloadResult)
            scheduleNextIfNotStopping(interval, reloadTask)
          case Left(ex) =>
            logger.error(s"[CLUSTERWIDE SETTINGS][${requestId.show}] Checking index settings failed: error", ex)
            scheduleNextIfNotStopping(interval, reloadTask)
        }
    }
    new CancelableWithRequestId(cancellable, requestId)
  }

  private def trySetNextReloadTask(nextTask: CancelableWithRequestId) = {
    reloadTaskState.updateAndGet {
      case ReloadTaskState.NotInitiated | ReloadTaskState.Running(_) =>
        ReloadTaskState.Running(nextTask)
      case ReloadTaskState.Stopped =>
        ReloadTaskState.Stopped
    }
  }

  private def logSettingsReloadResult(settingsReloadResult: (SettingsType, Either[ScheduledReloadError, Unit]))
                                     (implicit requestId: RequestId): Unit = settingsReloadResult match {
    case (_, Right(())) =>
    case (name, Left(ReloadingInProgress)) =>
      logger.debug(s"[CLUSTERWIDE SETTINGS][${requestId.show}] Reloading of ${name.show} engine in progress ... skipping")
    case (name, Left(EngineReloadError(IndexSettingsReloadError.ReloadError(RawSettingsReloadError.SettingsUpToDate(_))))) =>
      logger.debug(s"[CLUSTERWIDE SETTINGS][${requestId.show}] ${name.show} settings are up to date. Nothing to reload.")
    case (name, Left(EngineReloadError(IndexSettingsReloadError.ReloadError(RawSettingsReloadError.RorInstanceStopped)))) =>
      logger.debug(s"[CLUSTERWIDE SETTINGS][${requestId.show}] Stopping periodic ${name.show} settings check - application is being stopped")
    case (name, Left(EngineReloadError(IndexSettingsReloadError.ReloadError(RawSettingsReloadError.ReloadingFailed(startingFailure))))) =>
      logger.debug(s"[CLUSTERWIDE SETTINGS][${requestId.show}] ReadonlyREST ${name.show} engine starting failed: ${startingFailure.message.show}")
    case (name, Left(EngineReloadError(IndexSettingsReloadError.IndexLoadingSettingsError(error)))) =>
      logger.debug(s"[CLUSTERWIDE SETTINGS][${requestId.show}] Loading ${name.show} settings from index failed: ${error.show}")
  }

  private sealed trait SettingsType
  private object SettingsType {
    case object Main extends SettingsType
    case object Test extends SettingsType

    implicit val show: Show[SettingsType] = Show.show {
      case Main => "main"
      case Test => "test"
    }
  }

  private sealed trait ReloadTaskState
  private object ReloadTaskState {
    case object NotInitiated extends ReloadTaskState
    final case class Running(cancelable: CancelableWithRequestId) extends ReloadTaskState
    case object Stopped extends ReloadTaskState
  }

  private final class CancelableWithRequestId(cancelable: Cancelable, requestId: RequestId)
    extends Logging {

    def cancel(): Unit = {
      logger.debug(s"[CLUSTERWIDE SETTINGS][${requestId.show}] Scheduling next in-index settings check cancelled!")
      cancelable.cancel()
    }
  }
}

object DisabledRorSettingsAutoReloader extends RorSettingsAutoReloader with Logging {

  override def start(): Unit = {
    logger.info(s"[CLUSTERWIDE SETTINGS] Auto reloading of ReadonlyREST in-index settings disabled")
  }

  override def stop(): Task[Unit] = Task.unit
}