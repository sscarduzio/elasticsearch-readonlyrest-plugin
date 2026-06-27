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
package tech.beshu.ror.accesscontrol.audit.sink

import monix.eval.Task
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.RollingFileAppender
import org.apache.logging.log4j.core.appender.rolling.{
  CompositeTriggeringPolicy,
  DefaultRolloverStrategy,
  SizeBasedTriggeringPolicy
}
import org.apache.logging.log4j.core.layout.PatternLayout
import org.apache.logging.log4j.{LogManager, Logger}
import tech.beshu.ror.accesscontrol.audit.AuditingTool.AuditSettings.AuditSink.Config.RollingFileBasedSink.FileAppenderConfig
import tech.beshu.ror.accesscontrol.audit.CoreAuditSerializer
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.domain.RorAuditLoggerName

private[audit] final class RollingFileBasedAuditSink private (
    sinkName: Block.SinkName,
    serializer: CoreAuditSerializer,
    loggerName: RorAuditLoggerName,
    appender: RollingFileAppender
) extends TextBasedAuditSink(sinkName, serializer) {

  override protected val logger: Logger = LogManager.getLogger(loggerName.value.value)

  override def close(): Task[Unit] = Task.delay {
    val ctx = LogManager.getContext(false).asInstanceOf[LoggerContext]
    ctx.getLogger(loggerName.value.value).removeAppender(appender)
    ctx.updateLoggers()
    appender.stop()
  }

}

object RollingFileBasedAuditSink {

  final case class CreationError(message: String) extends AnyVal

  def create(
      sinkName: Block.SinkName,
      serializer: CoreAuditSerializer,
      loggerName: RorAuditLoggerName,
      config: FileAppenderConfig
  ): Task[Either[CreationError, RollingFileBasedAuditSink]] = {
    directoryError(config.filePath) match {
      case Some(err) => Task.pure(Left(err))
      case None      =>
        buildAndRegisterAppender(loggerName, config)
          .map(appender => Right(new RollingFileBasedAuditSink(sinkName, serializer, loggerName, appender)))
          .onErrorHandle(_ => Left(appenderCreationErrorMessage(config.filePath)))
    }
  }

  private def buildAndRegisterAppender(
      loggerName: RorAuditLoggerName,
      config: FileAppenderConfig
  ): Task[RollingFileAppender] =
    Task.delay {
      val ctx = LogManager.getContext(false).asInstanceOf[LoggerContext]
      val log4jConfig = ctx.getConfiguration

      val layout = PatternLayout
        .newBuilder()
        .withPattern("%msg%n")
        .withConfiguration(log4jConfig)
        .build()

      val triggeringPolicy = CompositeTriggeringPolicy.createPolicy(
        SizeBasedTriggeringPolicy.createPolicy(config.maxFileSize.value)
      )

      val rolloverStrategy = DefaultRolloverStrategy
        .newBuilder()
        .withMax(config.maxFiles.value.toString)
        .withConfig(log4jConfig)
        .build()

      val appender = Option(
        RollingFileAppenderFactory.create(
          s"RorAuditFile-${loggerName.value.value}",
          config.filePath.toString,
          config.filePath.toString + ".%i",
          layout,
          triggeringPolicy,
          rolloverStrategy,
          log4jConfig
        )
      ).getOrElse(throw new IllegalStateException("Appender builder returned null"))

      appender.start()
      ctx.getLogger(loggerName.value.value).addAppender(appender)
      ctx.updateLoggers()
      appender
    }

  private def directoryError(filePath: java.nio.file.Path): Option[CreationError] =
    filePath.getParent match {
      case null                        => None
      case dir if !dir.toFile.exists() =>
        Some(CreationError(s"Cannot create audit log file '$filePath': directory '$dir' does not exist"))
      case dir if !dir.toFile.canWrite =>
        Some(CreationError(s"Cannot create audit log file '$filePath': no write permission on directory '$dir'"))
      case _ => None
    }

  private def appenderCreationErrorMessage(filePath: java.nio.file.Path): CreationError =
    directoryError(filePath).getOrElse {
      if (filePath.toFile.exists() && !filePath.toFile.canWrite)
        CreationError(s"Cannot create audit log file '$filePath': no write permission on file '$filePath'")
      else
        CreationError(
          s"Cannot create audit log file '$filePath': ensure the path is valid and the Elasticsearch process has write permission"
        )
    }

}
