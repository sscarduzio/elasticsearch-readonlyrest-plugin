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
package tech.beshu.ror.utils

import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.{Configurator, Property}
import org.apache.logging.log4j.core.{Appender, LogEvent, LoggerContext}
import org.apache.logging.log4j.{Level, LogManager}

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.util.Using

object LogEventsCapture {

  final case class CapturedLogEvent(level: Level, message: String)

  /** Runs the code and returns the events which the given logger writes on the calling thread, from DEBUG
    * level up. Test suites run in parallel, so the captures run one at a time and ignore other threads.
    */
  def captureLogEvents(loggerName: String)(code: => Unit): List[CapturedLogEvent] = synchronized {
    val appender = new CapturingAppender(loggerName, Thread.currentThread().getName)
    Using.resource(attach(appender, loggerName))(_ => code)
    appender.events
  }

  private def attach(appender: Appender, loggerName: String): AutoCloseable = {
    val context = LogManager.getContext(false).asInstanceOf[LoggerContext]
    val previousLevel = context.getLogger(loggerName).getLevel
    Configurator.setLevel(loggerName, Level.DEBUG)
    val loggerConfig = context.getConfiguration.getLoggerConfig(loggerName)
    appender.start()
    loggerConfig.addAppender(appender, Level.DEBUG, null)
    context.updateLoggers()
    () => {
      loggerConfig.removeAppender(appender.getName)
      Configurator.setLevel(loggerName, previousLevel)
      appender.stop()
    }
  }

  private final class CapturingAppender(loggerName: String, threadName: String)
      extends AbstractAppender(s"capture-${UUID.randomUUID()}", null, null, true, Property.EMPTY_ARRAY) {

    private val captured = new AtomicReference(Vector.empty[CapturedLogEvent])

    override def append(event: LogEvent): Unit = {
      if (event.getLoggerName == loggerName && event.getThreadName == threadName) {
        captured.updateAndGet(_ :+ CapturedLogEvent(event.getLevel, event.getMessage.getFormattedMessage))
      }
    }

    def events: List[CapturedLogEvent] = captured.get.toList
  }

}
