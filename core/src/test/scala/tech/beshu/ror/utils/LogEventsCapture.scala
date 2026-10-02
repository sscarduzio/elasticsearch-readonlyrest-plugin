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
import org.apache.logging.log4j.core.{LogEvent, LoggerContext}
import org.apache.logging.log4j.{Level, LogManager}

import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

object LogEventsCapture {

  final case class CapturedLogEvent(level: Level, message: String)

  /** Runs the code and returns the events which the given logger writes on the calling thread, from DEBUG
    * level up. Test suites run in parallel, so the captures run one at a time and ignore other threads.
    */
  def captureLogEvents(loggerName: String)(code: => Unit): List[CapturedLogEvent] = synchronized {
    val events = new ConcurrentLinkedQueue[CapturedLogEvent]()
    val threadName = Thread.currentThread().getName
    val appender = new AbstractAppender(s"capture-${UUID.randomUUID()}", null, null, true, Property.EMPTY_ARRAY) {
      override def append(event: LogEvent): Unit = {
        if (event.getLoggerName == loggerName && event.getThreadName == threadName) {
          events.add(CapturedLogEvent(event.getLevel, event.getMessage.getFormattedMessage))
        }
      }
    }
    val context = LogManager.getContext(false).asInstanceOf[LoggerContext]
    val previousLevel = context.getLogger(loggerName).getLevel
    Configurator.setLevel(loggerName, Level.DEBUG)
    val loggerConfig = context.getConfiguration.getLoggerConfig(loggerName)
    appender.start()
    loggerConfig.addAppender(appender, Level.DEBUG, null)
    context.updateLoggers()
    try code
    finally {
      loggerConfig.removeAppender(appender.getName)
      Configurator.setLevel(loggerName, previousLevel)
      appender.stop()
    }
    events.asScala.toList
  }

}
