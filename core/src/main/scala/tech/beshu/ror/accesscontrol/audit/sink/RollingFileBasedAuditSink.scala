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
import org.apache.logging.log4j.core.appender.rolling.{CompositeTriggeringPolicy, DefaultRolloverStrategy, SizeBasedTriggeringPolicy}
import org.apache.logging.log4j.core.layout.PatternLayout
import org.apache.logging.log4j.{LogManager, Logger}
import org.json.JSONObject
import tech.beshu.ror.accesscontrol.audit.AclAuditLogSerializer
import tech.beshu.ror.accesscontrol.audit.AuditingTool.AuditSettings.AuditSink.Config.RollingFileBasedSink.FileAppenderConfig
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.domain.{RequestId, RorAuditLoggerName}
import tech.beshu.ror.audit.{AuditLogSerializer, AuditResponseContext}

private[audit] final class RollingFileBasedAuditSink private(sinkName: Block.SinkName,
                                                             serializer: AuditLogSerializer,
                                                             loggerName: RorAuditLoggerName,
                                                             appender: RollingFileAppender) extends BaseAuditSink(sinkName, serializer) {

  private val logger: Logger = LogManager.getLogger(loggerName.value.value)

  override protected def submit(event: AuditResponseContext, serializedEvent: JSONObject)
                               (implicit requestId: RequestId): Task[Unit] = Task {
    serializer match {
      case s: AclAuditLogSerializer =>
        logger.info(s"[${requestId.value}] ${s.formatMessage(event, logger.isDebugEnabled)}")
      case _ =>
        logger.info(serializedEvent.toString)
    }
  }

  override def close(): Task[Unit] = Task.delay {
    val ctx = LogManager.getContext(false).asInstanceOf[LoggerContext]
    ctx.getLogger(loggerName.value.value).removeAppender(appender)
    ctx.updateLoggers()
    appender.stop()
  }
}

object RollingFileBasedAuditSink {

  final case class CreationError(message: String) extends AnyVal

  def create(sinkName: Block.SinkName,
             serializer: AuditLogSerializer,
             loggerName: RorAuditLoggerName,
             config: FileAppenderConfig): Task[Either[CreationError, RollingFileBasedAuditSink]] =
    buildAndRegisterAppender(loggerName, config)
      .map(appender => Right(new RollingFileBasedAuditSink(sinkName, serializer, loggerName, appender)))
      .onErrorHandle(_ => Left(CreationError(appenderCreationErrorMessage(config.filePath))))

  private def buildAndRegisterAppender(loggerName: RorAuditLoggerName,
                                       config: FileAppenderConfig): Task[RollingFileAppender] =
    Task.delay {
      val ctx = LogManager.getContext(false).asInstanceOf[LoggerContext]
      val log4jConfig = ctx.getConfiguration

      val layout = PatternLayout.newBuilder()
        .withPattern("%msg%n")
        .withConfiguration(log4jConfig)
        .build()

      val triggeringPolicy = CompositeTriggeringPolicy.createPolicy(
        SizeBasedTriggeringPolicy.createPolicy(config.maxFileSize.value)
      )

      val rolloverStrategy = DefaultRolloverStrategy.newBuilder()
        .withMax(config.maxFiles.value.toString)
        .withConfig(log4jConfig)
        .build()

      val appender = Option(RollingFileAppenderFactory.create(
        s"RorAuditFile-${loggerName.value.value}",
        config.filePath.toString,
        config.filePath.toString + ".%i",
        layout,
        triggeringPolicy,
        rolloverStrategy,
        log4jConfig
      )).getOrElse(throw new IllegalStateException("Appender builder returned null"))

      appender.start()
      ctx.getLogger(loggerName.value.value).addAppender(appender)
      ctx.updateLoggers()
      appender
    }

  private def appenderCreationErrorMessage(filePath: java.nio.file.Path): String = {
    val parent = filePath.getParent
    if (parent != null && !parent.toFile.exists())
      s"Cannot create audit log file '$filePath': directory '$parent' does not exist"
    else if (parent != null && !parent.toFile.canWrite)
      s"Cannot create audit log file '$filePath': no write permission on directory '$parent'"
    else if (filePath.toFile.exists() && !filePath.toFile.canWrite)
      s"Cannot create audit log file '$filePath': no write permission on file '$filePath'"
    else
      s"Cannot create audit log file '$filePath': ensure the path is valid and the Elasticsearch process has write permission"
  }
}
