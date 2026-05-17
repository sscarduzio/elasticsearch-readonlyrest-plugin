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
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.domain.{RequestId, RorAuditLoggerName}
import tech.beshu.ror.audit.{AuditLogSerializer, AuditResponseContext}

private[audit] final class LogBasedAuditSink(sinkName: Block.SinkName,
                                             serializer: AuditLogSerializer,
                                             loggerName: RorAuditLoggerName,
                                             filePath: Option[java.nio.file.Path] = None,
                                             maxFileSize: String = "100MB",
                                             maxFiles: Int = 7) extends BaseAuditSink(sinkName, serializer) {

  private val logger: Logger = LogManager.getLogger(loggerName.value.value)
  private val fileAppender: Option[RollingFileAppender] = filePath.map { path =>
    buildAndStartAppender(loggerName.value.value, path, maxFileSize, maxFiles)
  }

  private def buildAndStartAppender(loggerName: String,
                                    path: java.nio.file.Path,
                                    maxFileSize: String,
                                    maxFiles: Int): RollingFileAppender = {
    val ctx = LogManager.getContext(false).asInstanceOf[LoggerContext]
    val config = ctx.getConfiguration

    val layout = PatternLayout.newBuilder()
      .withPattern("%msg%n")
      .withConfiguration(config)
      .build()

    val triggeringPolicy = CompositeTriggeringPolicy.createPolicy(
      SizeBasedTriggeringPolicy.createPolicy(maxFileSize)
    )

    val rolloverStrategy = DefaultRolloverStrategy.newBuilder()
      .withMax(maxFiles.toString)
      .withConfig(config)
      .build()

    val appender = RollingFileAppenderFactory.create(
      s"RorAuditFile-$loggerName",
      path.toString,
      path.toString + ".%i",
      layout,
      triggeringPolicy,
      rolloverStrategy,
      config
    )

    appender.start()
    ctx.getLogger(loggerName).addAppender(appender)
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
    fileAppender.foreach { appender =>
      val ctx = LogManager.getContext(false).asInstanceOf[LoggerContext]
      ctx.getLogger(loggerName.value.value).removeAppender(appender)
      ctx.updateLoggers()
      appender.stop()
    }
  }
}