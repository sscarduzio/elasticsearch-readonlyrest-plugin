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
import org.apache.logging.log4j.{LogManager, Logger}
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.RollingFileAppender
import org.apache.logging.log4j.core.appender.rolling.{CompositeTriggeringPolicy, DefaultRolloverStrategy, SizeBasedTriggeringPolicy}
import org.apache.logging.log4j.core.layout.PatternLayout
import org.json.JSONObject
import tech.beshu.ror.accesscontrol.audit.AclAuditLogSerializer
import tech.beshu.ror.accesscontrol.audit.AuditingTool.AuditSettings.AuditSink.Config.RollingFileBasedSink.FileAppenderConfig
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.domain.{RequestId, RorAuditLoggerName}
import tech.beshu.ror.audit.{AuditLogSerializer, AuditResponseContext}

private[audit] final class RollingFileBasedAuditSink(sinkName: Block.SinkName,
                                                     serializer: AuditLogSerializer,
                                                     loggerName: RorAuditLoggerName,
                                                     config: FileAppenderConfig) extends BaseAuditSink(sinkName, serializer) {

  private val logger: Logger = LogManager.getLogger(loggerName.value.value)
  private val fileAppender: RollingFileAppender = buildAndStartAppender()

  private def buildAndStartAppender(): RollingFileAppender = {
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

    val appender = RollingFileAppenderFactory.create(
      s"RorAuditFile-${loggerName.value.value}",
      config.filePath.toString,
      config.filePath.toString + ".%i",
      layout,
      triggeringPolicy,
      rolloverStrategy,
      log4jConfig
    )

    appender.start()
    ctx.getLogger(loggerName.value.value).addAppender(appender)
    ctx.updateLoggers()
    appender
  }

  override protected def submit(event: AuditResponseContext, serializedEvent: JSONObject)
                               (implicit requestId: RequestId): Task[Unit] = Task {
    val msg = serializedEvent.optString(AclAuditLogSerializer.messageField, null)
    if (msg != null) logger.info(msg)
    else logger.info(serializedEvent.toString)
  }

  override def close(): Task[Unit] = Task.delay {
    val ctx = LogManager.getContext(false).asInstanceOf[LoggerContext]
    ctx.getLogger(loggerName.value.value).removeAppender(fileAppender)
    ctx.updateLoggers()
    fileAppender.stop()
  }
}