package tech.beshu.ror.utils

import org.apache.logging.log4j.scala.Logger

class LoggerOps(logger: Logger) {

  def errorEx(message: String, throwable: Throwable): Unit = {
    if(logger.delegate.isDebugEnabled) logger.error(message, throwable)
    else logger.error(message)
  }
}

object LoggerOps {
  implicit def toLoggerOps(logger: Logger): LoggerOps = new LoggerOps(logger)
}
