/*
 * Copyright https://github.com/apache/logging-log4j-scala
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache license, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the license for the specific language governing permissions and
 * limitations under the license.
 */
package tech.beshu.ror.utils.slf4j

import org.apache.logging.log4j.message.Message
import org.apache.logging.log4j.{Level, Marker}

import scala.quoted.*

/**
 * Inspired from [[https://github.com/typesafehub/scalalogging ScalaLogging]].
 */
private object LoggerMacro {
  // Trace

  def traceMsg(underlying: Expr[Logger], message: Expr[Message])(
      using Quotes
  ): Expr[Unit] = {
    '{ if ($underlying.delegate.isEnabled(Level.TRACE)) $underlying.delegate.trace($message) }
  }

  def traceMsgThrowable(underlying: Expr[Logger], message: Expr[Message], throwable: Expr[Throwable])(
      using Quotes
  ): Expr[Unit] = {
    '{ if ($underlying.delegate.isEnabled(Level.TRACE)) $underlying.delegate.trace($message, $throwable) }
  }

  def traceMarkerMsg(underlying: Expr[Logger], marker: Expr[Marker], message: Expr[Message])(
      using Quotes
  ): Expr[Unit] = {
    '{ if ($underlying.delegate.isEnabled(Level.TRACE, $marker)) $underlying.delegate.trace($marker, $message) }
  }

  def traceMarkerMsgThrowable(
      underlying: Expr[Logger],
      marker: Expr[Marker],
      message: Expr[Message],
      throwable: Expr[Throwable]
  )(
      using Quotes
  ): Expr[Unit] = {
    '{
      if ($underlying.delegate.isEnabled(Level.TRACE, $marker))
        $underlying.delegate.trace($marker, $message, $throwable)
    }
  }

  def traceCseq(underlying: Expr[Logger], message: Expr[CharSequence])(
      using Quotes
  ): Expr[Unit] = {
    val (messageFormat, args) = deconstructInterpolatedMessage(message)
    logMessageArgs(underlying, '{ Level.TRACE }, messageFormat, Expr.ofSeq(args))
  }

  def traceCseqThrowable(underlying: Expr[Logger], message: Expr[CharSequence], throwable: Expr[Throwable])(
      using Quotes
  ): Expr[Unit] = {
    val (messageFormat, args) = deconstructInterpolatedMessage(message)
    logMessageArgsThrowable(underlying, '{ Level.TRACE }, messageFormat, Expr.ofSeq(args), throwable)
  }

  def traceMarkerCseq(underlying: Expr[Logger], marker: Expr[Marker], message: Expr[CharSequence])(
      using Quotes
  ): Expr[Unit] = {
    val (messageFormat, args) = deconstructInterpolatedMessage(message)
    logMarkerMessageArgs(underlying, '{ Level.TRACE }, marker, messageFormat, Expr.ofSeq(args))
  }

  def traceMarkerCseqThrowable(
      underlying: Expr[Logger],
      marker: Expr[Marker],
      message: Expr[CharSequence],
      throwable: Expr[Throwable]
  )(
      using Quotes
  ): Expr[Unit] = {
    val (messageFormat, args) = deconstructInterpolatedMessage(message)
    logMarkerMessageArgsThrowable(underlying, '{ Level.TRACE }, marker, messageFormat, Expr.ofSeq(args), throwable)
  }

  def traceObject(underlying: Expr[Logger], message: Expr[AnyRef])(
      using Quotes
  ): Expr[Unit] = {
    '{ if ($underlying.delegate.isEnabled(Level.TRACE)) $underlying.delegate.trace($message) }
  }

  def traceObjectThrowable(underlying: Expr[Logger], message: Expr[AnyRef], throwable: Expr[Throwable])(
      using Quotes
  ): Expr[Unit] = {
    '{ if ($underlying.delegate.isEnabled(Level.TRACE)) $underlying.delegate.trace($message, $throwable) }
  }

  def traceMarkerObject(underlying: Expr[Logger], marker: Expr[Marker], message: Expr[AnyRef])(
      using Quotes
  ): Expr[Unit] = {
    '{ if ($underlying.delegate.isEnabled(Level.TRACE, $marker)) $underlying.delegate.trace($marker, $message) }
  }

  def traceMarkerObjectThrowable(
      underlying: Expr[Logger],
      marker: Expr[Marker],
      message: Expr[AnyRef],
      throwable: Expr[Throwable]
  )(
      using Quotes
  ): Expr[Unit] = {
    '{
      if ($underlying.delegate.isEnabled(Level.TRACE, $marker))
        $underlying.delegate.trace($marker, $message, $throwable)
    }
  }

  // Debug

  def debugMsg(underlying: Expr[Logger], message: Expr[Message])(
      using Quotes
  ): Expr[Unit] = {
    '{ if ($underlying.delegate.isEnabled(Level.DEBUG)) $underlying.delegate.debug($message) }
  }

  def debugMsgThrowable(underlying: Expr[Logger], message: Expr[Message], throwable: Expr[Throwable])(
      using Quotes
  ): Expr[Unit] = {
    '{ if ($underlying.delegate.isEnabled(Level.DEBUG)) $underlying.delegate.debug($message, $throwable) }
  }

  def debugMarkerMsg(underlying: Expr[Logger], marker: Expr[Marker], message: Expr[Message])(
      using Quotes
  ): Expr[Unit] = {
    '{ if ($underlying.delegate.isEnabled(Level.DEBUG, $marker)) $underlying.delegate.debug($marker, $message) }
  }

  def debugMarkerMsgThrowable(
      underlying: Expr[Logger],
      marker: Expr[Marker],
      message: Expr[Message],
      throwable: Expr[Throwable]
  )(
      using Quotes
  ): Expr[Unit] = {
    '{
      if ($underlying.delegate.isEnabled(Level.DEBUG, $marker))
        $underlying.delegate.debug($marker, $message, $throwable)
    }
  }

  def debugCseq(underlying: Expr[Logger], message: Expr[CharSequence])(
      using Quotes
  ): Expr[Unit] = {
    val (messageFormat, args) = deconstructInterpolatedMessage(message)
    logMessageArgs(underlying, '{ Level.DEBUG }, messageFormat, Expr.ofSeq(args))
  }

  def debugCseqThrowable(underlying: Expr[Logger], message: Expr[CharSequence], throwable: Expr[Throwable])(
      using Quotes
  ): Expr[Unit] = {
    val (messageFormat, args) = deconstructInterpolatedMessage(message)
    logMessageArgsThrowable(underlying, '{ Level.DEBUG }, messageFormat, Expr.ofSeq(args), throwable)
  }

  def debugMarkerCseq(underlying: Expr[Logger], marker: Expr[Marker], message: Expr[CharSequence])(
      using Quotes
  ): Expr[Unit] = {
    val (messageFormat, args) = deconstructInterpolatedMessage(message)
    logMarkerMessageArgs(underlying, '{ Level.DEBUG }, marker, messageFormat, Expr.ofSeq(args))
  }

  def debugMarkerCseqThrowable(
      underlying: Expr[Logger],
      marker: Expr[Marker],
      message: Expr[CharSequence],
      throwable: Expr[Throwable]
  )(
      using Quotes
  ): Expr[Unit] = {
    val (messageFormat, args) = deconstructInterpolatedMessage(message)
    logMarkerMessageArgsThrowable(underlying, '{ Level.DEBUG }, marker, messageFormat, Expr.ofSeq(args), throwable)
  }

  def debugObject(underlying: Expr[Logger], message: Expr[AnyRef])(
      using Quotes
  ): Expr[Unit] = {
    '{ if ($underlying.delegate.isEnabled(Level.DEBUG)) $underlying.delegate.debug($message) }
  }

  def debugObjectThrowable(underlying: Expr[Logger], message: Expr[AnyRef], throwable: Expr[Throwable])(
      using Quotes
  ): Expr[Unit] = {
    '{ if ($underlying.delegate.isEnabled(Level.DEBUG)) $underlying.delegate.debug($message, $throwable) }
  }

  def debugMarkerObject(underlying: Expr[Logger], marker: Expr[Marker], message: Expr[AnyRef])(
      using Quotes
  ): Expr[Unit] = {
    '{ if ($underlying.delegate.isEnabled(Level.DEBUG, $marker)) $underlying.delegate.debug($marker, $message) }
  }

  def debugMarkerObjectThrowable(
      underlying: Expr[Logger],
      marker: Expr[Marker],
      message: Expr[AnyRef],
      throwable: Expr[Throwable]
  )(
      using Quotes
  ): Expr[Unit] = {
    '{
      if ($underlying.delegate.isEnabled(Level.DEBUG, $marker))
        $underlying.delegate.debug($marker, $message, $throwable)
    }
  }

  // Info

  def infoMsg(underlying: Expr[Logger], message: Expr[Message])(
      using Quotes
  ): Expr[Unit] = {
    '{ if ($underlying.delegate.isEnabled(Level.INFO)) $underlying.delegate.info($message) }
  }

  def infoMsgThrowable(underlying: Expr[Logger], message: Expr[Message], throwable: Expr[Throwable])(
      using Quotes
  ): Expr[Unit] = {
    '{ if ($underlying.delegate.isEnabled(Level.INFO)) $underlying.delegate.info($message, $throwable) }
  }

  def infoMarkerMsg(underlying: Expr[Logger], marker: Expr[Marker], message: Expr[Message])(
      using Quotes
  ): Expr[Unit] = {
    '{ if ($underlying.delegate.isEnabled(Level.INFO, $marker)) $underlying.delegate.info($marker, $message) }
  }

  def infoMarkerMsgThrowable(
      underlying: Expr[Logger],
      marker: Expr[Marker],
      message: Expr[Message],
      throwable: Expr[Throwable]
  )(
      using Quotes
  ): Expr[Unit] = {
    '{
      if ($underlying.delegate.isEnabled(Level.INFO, $marker)) $underlying.delegate.info($marker, $message, $throwable)
    }
  }

  def infoCseq(underlying: Expr[Logger], message: Expr[CharSequence])(
      using Quotes
  ): Expr[Unit] = {
    val (messageFormat, args) = deconstructInterpolatedMessage(message)
    logMessageArgs(underlying, '{ Level.INFO }, messageFormat, Expr.ofSeq(args))
  }

  def infoCseqThrowable(underlying: Expr[Logger], message: Expr[CharSequence], throwable: Expr[Throwable])(
      using Quotes
  ): Expr[Unit] = {
    val (messageFormat, args) = deconstructInterpolatedMessage(message)
    logMessageArgsThrowable(underlying, '{ Level.INFO }, messageFormat, Expr.ofSeq(args), throwable)
  }

  def infoMarkerCseq(underlying: Expr[Logger], marker: Expr[Marker], message: Expr[CharSequence])(
      using Quotes
  ): Expr[Unit] = {
    val (messageFormat, args) = deconstructInterpolatedMessage(message)
    logMarkerMessageArgs(underlying, '{ Level.INFO }, marker, messageFormat, Expr.ofSeq(args))
  }

  def infoMarkerCseqThrowable(
      underlying: Expr[Logger],
      marker: Expr[Marker],
      message: Expr[CharSequence],
      throwable: Expr[Throwable]
  )(
      using Quotes
  ): Expr[Unit] = {
    val (messageFormat, args) = deconstructInterpolatedMessage(message)
    logMarkerMessageArgsThrowable(underlying, '{ Level.INFO }, marker, messageFormat, Expr.ofSeq(args), throwable)
  }

  def infoObject(underlying: Expr[Logger], message: Expr[AnyRef])(
      using Quotes
  ): Expr[Unit] = {
    '{ if ($underlying.delegate.isEnabled(Level.INFO)) $underlying.delegate.info($message) }
  }

  def infoObjectThrowable(underlying: Expr[Logger], message: Expr[AnyRef], throwable: Expr[Throwable])(
      using Quotes
  ): Expr[Unit] = {
    '{ if ($underlying.delegate.isEnabled(Level.INFO)) $underlying.delegate.info($message, $throwable) }
  }

  def infoMarkerObject(underlying: Expr[Logger], marker: Expr[Marker], message: Expr[AnyRef])(
      using Quotes
  ): Expr[Unit] = {
    '{ if ($underlying.delegate.isEnabled(Level.INFO, $marker)) $underlying.delegate.info($marker, $message) }
  }

  def infoMarkerObjectThrowable(
      underlying: Expr[Logger],
      marker: Expr[Marker],
      message: Expr[AnyRef],
      throwable: Expr[Throwable]
  )(
      using Quotes
  ): Expr[Unit] = {
    '{
      if ($underlying.delegate.isEnabled(Level.INFO, $marker)) $underlying.delegate.info($marker, $message, $throwable)
    }
  }

  // Warn

  def warnMsg(underlying: Expr[Logger], message: Expr[Message])(
      using Quotes
  ): Expr[Unit] = {
    '{ if ($underlying.delegate.isEnabled(Level.WARN)) $underlying.delegate.warn($message) }
  }

  def warnMsgThrowable(underlying: Expr[Logger], message: Expr[Message], throwable: Expr[Throwable])(
      using Quotes
  ): Expr[Unit] = {
    '{ if ($underlying.delegate.isEnabled(Level.WARN)) $underlying.delegate.warn($message, $throwable) }
  }

  def warnMarkerMsg(underlying: Expr[Logger], marker: Expr[Marker], message: Expr[Message])(
      using Quotes
  ): Expr[Unit] = {
    '{ if ($underlying.delegate.isEnabled(Level.WARN, $marker)) $underlying.delegate.warn($marker, $message) }
  }

  def warnMarkerMsgThrowable(
      underlying: Expr[Logger],
      marker: Expr[Marker],
      message: Expr[Message],
      throwable: Expr[Throwable]
  )(
      using Quotes
  ): Expr[Unit] = {
    '{
      if ($underlying.delegate.isEnabled(Level.WARN, $marker)) $underlying.delegate.warn($marker, $message, $throwable)
    }
  }

  def warnCseq(underlying: Expr[Logger], message: Expr[CharSequence])(
      using Quotes
  ): Expr[Unit] = {
    val (messageFormat, args) = deconstructInterpolatedMessage(message)
    logMessageArgs(underlying, '{ Level.WARN }, messageFormat, Expr.ofSeq(args))
  }

  def warnCseqThrowable(underlying: Expr[Logger], message: Expr[CharSequence], throwable: Expr[Throwable])(
      using Quotes
  ): Expr[Unit] = {
    val (messageFormat, args) = deconstructInterpolatedMessage(message)
    logMessageArgsThrowable(underlying, '{ Level.WARN }, messageFormat, Expr.ofSeq(args), throwable)
  }

  def warnMarkerCseq(underlying: Expr[Logger], marker: Expr[Marker], message: Expr[CharSequence])(
      using Quotes
  ): Expr[Unit] = {
    val (messageFormat, args) = deconstructInterpolatedMessage(message)
    logMarkerMessageArgs(underlying, '{ Level.WARN }, marker, messageFormat, Expr.ofSeq(args))
  }

  def warnMarkerCseqThrowable(
      underlying: Expr[Logger],
      marker: Expr[Marker],
      message: Expr[CharSequence],
      throwable: Expr[Throwable]
  )(
      using Quotes
  ): Expr[Unit] = {
    val (messageFormat, args) = deconstructInterpolatedMessage(message)
    logMarkerMessageArgsThrowable(underlying, '{ Level.WARN }, marker, messageFormat, Expr.ofSeq(args), throwable)
  }

  def warnObject(underlying: Expr[Logger], message: Expr[AnyRef])(
      using Quotes
  ): Expr[Unit] = {
    '{ if ($underlying.delegate.isEnabled(Level.WARN)) $underlying.delegate.warn($message) }
  }

  def warnObjectThrowable(underlying: Expr[Logger], message: Expr[AnyRef], throwable: Expr[Throwable])(
      using Quotes
  ): Expr[Unit] = {
    '{ if ($underlying.delegate.isEnabled(Level.WARN)) $underlying.delegate.warn($message, $throwable) }
  }

  def warnMarkerObject(underlying: Expr[Logger], marker: Expr[Marker], message: Expr[AnyRef])(
      using Quotes
  ): Expr[Unit] = {
    '{ if ($underlying.delegate.isEnabled(Level.WARN, $marker)) $underlying.delegate.warn($marker, $message) }
  }

  def warnMarkerObjectThrowable(
      underlying: Expr[Logger],
      marker: Expr[Marker],
      message: Expr[AnyRef],
      throwable: Expr[Throwable]
  )(
      using Quotes
  ): Expr[Unit] = {
    '{
      if ($underlying.delegate.isEnabled(Level.WARN, $marker)) $underlying.delegate.warn($marker, $message, $throwable)
    }
  }

  // Error

  def errorMsg(underlying: Expr[Logger], message: Expr[Message])(
      using Quotes
  ): Expr[Unit] = {
    '{ if ($underlying.delegate.isEnabled(Level.ERROR)) $underlying.delegate.error($message) }
  }

  def errorMsgThrowable(underlying: Expr[Logger], message: Expr[Message], throwable: Expr[Throwable])(
      using Quotes
  ): Expr[Unit] = {
    '{ if ($underlying.delegate.isEnabled(Level.ERROR)) $underlying.delegate.error($message, $throwable) }
  }

  def errorMarkerMsg(underlying: Expr[Logger], marker: Expr[Marker], message: Expr[Message])(
      using Quotes
  ): Expr[Unit] = {
    '{ if ($underlying.delegate.isEnabled(Level.ERROR, $marker)) $underlying.delegate.error($marker, $message) }
  }

  def errorMarkerMsgThrowable(
      underlying: Expr[Logger],
      marker: Expr[Marker],
      message: Expr[Message],
      throwable: Expr[Throwable]
  )(
      using Quotes
  ): Expr[Unit] = {
    '{
      if ($underlying.delegate.isEnabled(Level.ERROR, $marker))
        $underlying.delegate.error($marker, $message, $throwable)
    }
  }

  def errorCseq(underlying: Expr[Logger], message: Expr[CharSequence])(
      using Quotes
  ): Expr[Unit] = {
    val (messageFormat, args) = deconstructInterpolatedMessage(message)
    logMessageArgs(underlying, '{ Level.ERROR }, messageFormat, Expr.ofSeq(args))
  }

  def errorCseqThrowable(underlying: Expr[Logger], message: Expr[CharSequence], throwable: Expr[Throwable])(
      using Quotes
  ): Expr[Unit] = {
    val (messageFormat, args) = deconstructInterpolatedMessage(message)
    logMessageArgsThrowable(underlying, '{ Level.ERROR }, messageFormat, Expr.ofSeq(args), throwable)
  }

  def errorMarkerCseq(underlying: Expr[Logger], marker: Expr[Marker], message: Expr[CharSequence])(
      using Quotes
  ): Expr[Unit] = {
    val (messageFormat, args) = deconstructInterpolatedMessage(message)
    logMarkerMessageArgs(underlying, '{ Level.ERROR }, marker, messageFormat, Expr.ofSeq(args))
  }

  def errorMarkerCseqThrowable(
      underlying: Expr[Logger],
      marker: Expr[Marker],
      message: Expr[CharSequence],
      throwable: Expr[Throwable]
  )(
      using Quotes
  ): Expr[Unit] = {
    val (messageFormat, args) = deconstructInterpolatedMessage(message)
    logMarkerMessageArgsThrowable(underlying, '{ Level.ERROR }, marker, messageFormat, Expr.ofSeq(args), throwable)
  }

  def errorObject(underlying: Expr[Logger], message: Expr[AnyRef])(
      using Quotes
  ): Expr[Unit] = {
    '{ if ($underlying.delegate.isEnabled(Level.ERROR)) $underlying.delegate.error($message) }
  }

  def errorObjectThrowable(underlying: Expr[Logger], message: Expr[AnyRef], throwable: Expr[Throwable])(
      using Quotes
  ): Expr[Unit] = {
    '{ if ($underlying.delegate.isEnabled(Level.ERROR)) $underlying.delegate.error($message, $throwable) }
  }

  def errorMarkerObject(underlying: Expr[Logger], marker: Expr[Marker], message: Expr[AnyRef])(
      using Quotes
  ): Expr[Unit] = {
    '{ if ($underlying.delegate.isEnabled(Level.ERROR, $marker)) $underlying.delegate.error($marker, $message) }
  }

  def errorMarkerObjectThrowable(
      underlying: Expr[Logger],
      marker: Expr[Marker],
      message: Expr[AnyRef],
      throwable: Expr[Throwable]
  )(
      using Quotes
  ): Expr[Unit] = {
    '{
      if ($underlying.delegate.isEnabled(Level.ERROR, $marker))
        $underlying.delegate.error($marker, $message, $throwable)
    }
  }

  // Fatal

  def fatalMsg(underlying: Expr[Logger], message: Expr[Message])(
      using Quotes
  ): Expr[Unit] = {
    '{ if ($underlying.delegate.isEnabled(Level.FATAL)) $underlying.delegate.fatal($message) }
  }

  def fatalMsgThrowable(underlying: Expr[Logger], message: Expr[Message], throwable: Expr[Throwable])(
      using Quotes
  ): Expr[Unit] = {
    '{ if ($underlying.delegate.isEnabled(Level.FATAL)) $underlying.delegate.fatal($message, $throwable) }
  }

  def fatalMarkerMsg(underlying: Expr[Logger], marker: Expr[Marker], message: Expr[Message])(
      using Quotes
  ): Expr[Unit] = {
    '{ if ($underlying.delegate.isEnabled(Level.FATAL, $marker)) $underlying.delegate.fatal($marker, $message) }
  }

  def fatalMarkerMsgThrowable(
      underlying: Expr[Logger],
      marker: Expr[Marker],
      message: Expr[Message],
      throwable: Expr[Throwable]
  )(
      using Quotes
  ): Expr[Unit] = {
    '{
      if ($underlying.delegate.isEnabled(Level.FATAL, $marker))
        $underlying.delegate.fatal($marker, $message, $throwable)
    }
  }

  def fatalCseq(underlying: Expr[Logger], message: Expr[CharSequence])(
      using Quotes
  ): Expr[Unit] = {
    val (messageFormat, args) = deconstructInterpolatedMessage(message)
    logMessageArgs(underlying, '{ Level.FATAL }, messageFormat, Expr.ofSeq(args))
  }

  def fatalCseqThrowable(underlying: Expr[Logger], message: Expr[CharSequence], throwable: Expr[Throwable])(
      using Quotes
  ): Expr[Unit] = {
    val (messageFormat, args) = deconstructInterpolatedMessage(message)
    logMessageArgsThrowable(underlying, '{ Level.FATAL }, messageFormat, Expr.ofSeq(args), throwable)
  }

  def fatalMarkerCseq(underlying: Expr[Logger], marker: Expr[Marker], message: Expr[CharSequence])(
      using Quotes
  ): Expr[Unit] = {
    val (messageFormat, args) = deconstructInterpolatedMessage(message)
    logMarkerMessageArgs(underlying, '{ Level.FATAL }, marker, messageFormat, Expr.ofSeq(args))
  }

  def fatalMarkerCseqThrowable(
      underlying: Expr[Logger],
      marker: Expr[Marker],
      message: Expr[CharSequence],
      throwable: Expr[Throwable]
  )(
      using Quotes
  ): Expr[Unit] = {
    val (messageFormat, args) = deconstructInterpolatedMessage(message)
    logMarkerMessageArgsThrowable(underlying, '{ Level.FATAL }, marker, messageFormat, Expr.ofSeq(args), throwable)
  }

  def fatalObject(underlying: Expr[Logger], message: Expr[AnyRef])(
      using Quotes
  ): Expr[Unit] = {
    '{ if ($underlying.delegate.isEnabled(Level.FATAL)) $underlying.delegate.fatal($message) }
  }

  def fatalObjectThrowable(underlying: Expr[Logger], message: Expr[AnyRef], throwable: Expr[Throwable])(
      using Quotes
  ): Expr[Unit] = {
    '{ if ($underlying.delegate.isEnabled(Level.FATAL)) $underlying.delegate.fatal($message, $throwable) }
  }

  def fatalMarkerObject(underlying: Expr[Logger], marker: Expr[Marker], message: Expr[AnyRef])(
      using Quotes
  ): Expr[Unit] = {
    '{ if ($underlying.delegate.isEnabled(Level.FATAL, $marker)) $underlying.delegate.fatal($marker, $message) }
  }

  def fatalMarkerObjectThrowable(
      underlying: Expr[Logger],
      marker: Expr[Marker],
      message: Expr[AnyRef],
      throwable: Expr[Throwable]
  )(
      using Quotes
  ): Expr[Unit] = {
    '{
      if ($underlying.delegate.isEnabled(Level.FATAL, $marker))
        $underlying.delegate.fatal($marker, $message, $throwable)
    }
  }

  def logMsg(underlying: Expr[Logger], level: Expr[Level], message: Expr[Message])(
      using Quotes
  ): Expr[Unit] = {
    '{ if ($underlying.delegate.isEnabled($level)) $underlying.delegate.log($level, $message) }
  }

  def logMsgThrowable(underlying: Expr[Logger], level: Expr[Level], message: Expr[Message], throwable: Expr[Throwable])(
      using Quotes
  ): Expr[Unit] = {
    '{ if ($underlying.delegate.isEnabled($level)) $underlying.delegate.log($level, $message, $throwable) }
  }

  def logObject(underlying: Expr[Logger], level: Expr[Level], message: Expr[AnyRef])(
      using Quotes
  ): Expr[Unit] = {
    '{ if ($underlying.delegate.isEnabled($level)) $underlying.delegate.log($level, $message) }
  }

  def logObjectThrowable(
      underlying: Expr[Logger],
      level: Expr[Level],
      message: Expr[AnyRef],
      throwable: Expr[Throwable]
  )(
      using Quotes
  ): Expr[Unit] = {
    '{ if ($underlying.delegate.isEnabled($level)) $underlying.delegate.log($level, $message, $throwable) }
  }

  def logCseq(underlying: Expr[Logger], level: Expr[Level], message: Expr[CharSequence])(
      using Quotes
  ): Expr[Unit] = {
    val (messageFormat, args) = deconstructInterpolatedMessage(message)
    logMessageArgs(underlying, level, messageFormat, Expr.ofSeq(args))
  }

  def logCseqThrowable(
      underlying: Expr[Logger],
      level: Expr[Level],
      message: Expr[CharSequence],
      throwable: Expr[Throwable]
  )(
      using Quotes
  ): Expr[Unit] = {
    val (messageFormat, args) = deconstructInterpolatedMessage(message)
    logMessageArgsThrowable(underlying, level, messageFormat, Expr.ofSeq(args), throwable)
  }

  def logMarkerMsg(underlying: Expr[Logger], level: Expr[Level], marker: Expr[Marker], message: Expr[Message])(
      using Quotes
  ): Expr[Unit] = {
    '{ if ($underlying.delegate.isEnabled($level, $marker)) $underlying.delegate.log($level, $marker, $message) }
  }

  def logMarkerMsgThrowable(
      underlying: Expr[Logger],
      level: Expr[Level],
      marker: Expr[Marker],
      message: Expr[Message],
      throwable: Expr[Throwable]
  )(
      using Quotes
  ): Expr[Unit] = {
    '{
      if ($underlying.delegate.isEnabled($level, $marker))
        $underlying.delegate.log($level, $marker, $message, $throwable)
    }
  }

  def logMarkerObject(underlying: Expr[Logger], level: Expr[Level], marker: Expr[Marker], message: Expr[AnyRef])(
      using Quotes
  ): Expr[Unit] = {
    '{ if ($underlying.delegate.isEnabled($level, $marker)) $underlying.delegate.log($level, $marker, $message) }
  }

  def logMarkerObjectThrowable(
      underlying: Expr[Logger],
      level: Expr[Level],
      marker: Expr[Marker],
      message: Expr[AnyRef],
      throwable: Expr[Throwable]
  )(
      using Quotes
  ): Expr[Unit] = {
    '{
      if ($underlying.delegate.isEnabled($level, $marker))
        $underlying.delegate.log($level, $marker, $message, $throwable)
    }
  }

  def logMarkerCseq(underlying: Expr[Logger], level: Expr[Level], marker: Expr[Marker], message: Expr[CharSequence])(
      using Quotes
  ): Expr[Unit] = {
    val (messageFormat, args) = deconstructInterpolatedMessage(message)
    logMarkerMessageArgs(underlying, level, marker, messageFormat, Expr.ofSeq(args))
  }

  def logMarkerCseqThrowable(
      underlying: Expr[Logger],
      level: Expr[Level],
      marker: Expr[Marker],
      message: Expr[CharSequence],
      throwable: Expr[Throwable]
  )(
      using Quotes
  ): Expr[Unit] = {
    val (messageFormat, args) = deconstructInterpolatedMessage(message)
    logMarkerMessageArgsThrowable(underlying, level, marker, messageFormat, Expr.ofSeq(args), throwable)
  }

  private def logMessageArgs(
      underlying: Expr[Logger],
      level: Expr[Level],
      message: Expr[CharSequence],
      args: Expr[Seq[Any]]
  )(
      using Quotes
  ) = {
    val anyRefArgs = formatArgs(args)
    if (anyRefArgs.isEmpty)
      '{ if ($underlying.delegate.isEnabled($level)) $underlying.logMessage($level, null, $message.toString, null) }
    else if (anyRefArgs.length == 1)
      '{
        if ($underlying.delegate.isEnabled($level))
          $underlying.delegate.log($level, $message.toString, ${ anyRefArgs.head })
      }
    else
      '{
        if ($underlying.delegate.isEnabled($level))
          $underlying.delegate.log($level, $message.toString, ${ Expr.ofSeq(anyRefArgs) }*)
      }
  }

  private def logMessageArgsThrowable(
      underlying: Expr[Logger],
      level: Expr[Level],
      message: Expr[CharSequence],
      args: Expr[Seq[Any]],
      throwable: Expr[Throwable]
  )(
      using Quotes
  ) = {
    val anyRefArgs = formatArgs(args)
    if (anyRefArgs.isEmpty)
      '{
        if ($underlying.delegate.isEnabled($level)) $underlying.logMessage($level, null, $message.toString, $throwable)
      }
    else if (anyRefArgs.length == 1)
      '{
        if ($underlying.delegate.isEnabled($level))
          $underlying.delegate.log($level, $message.toString, ${ anyRefArgs.head }, $throwable)
      }
    else {
      val extendedArgs = anyRefArgs :+ throwable
      '{
        if ($underlying.delegate.isEnabled($level))
          $underlying.delegate.log($level, $message.toString, ${ Expr.ofSeq(extendedArgs) }*)
      }
    }
  }

  private def logMarkerMessageArgs(
      underlying: Expr[Logger],
      level: Expr[Level],
      marker: Expr[Marker],
      message: Expr[CharSequence],
      args: Expr[Seq[Any]]
  )(
      using Quotes
  ) = {
    val anyRefArgs = formatArgs(args)
    if (anyRefArgs.isEmpty)
      '{
        if ($underlying.delegate.isEnabled($level, $marker))
          $underlying.logMessage($level, $marker, $message.toString, null)
      }
    else if (anyRefArgs.length == 1)
      '{
        if ($underlying.delegate.isEnabled($level, $marker))
          $underlying.delegate.log($level, $marker, $message.toString, ${ anyRefArgs.head })
      }
    else
      '{
        if ($underlying.delegate.isEnabled($level, $marker))
          $underlying.delegate.log($level, $marker, $message.toString, ${ Expr.ofSeq(anyRefArgs) }*)
      }
  }

  private def logMarkerMessageArgsThrowable(
      underlying: Expr[Logger],
      level: Expr[Level],
      marker: Expr[Marker],
      message: Expr[CharSequence],
      args: Expr[Seq[Any]],
      throwable: Expr[Throwable]
  )(
      using Quotes
  ) = {
    val anyRefArgs = formatArgs(args)
    if (anyRefArgs.isEmpty)
      '{
        if ($underlying.delegate.isEnabled($level, $marker))
          $underlying.logMessage($level, $marker, $message.toString, $throwable)
      }
    else if (anyRefArgs.length == 1)
      '{
        if ($underlying.delegate.isEnabled($level, $marker))
          $underlying.delegate.log($level, $marker, $message.toString, ${ anyRefArgs.head }, $throwable)
      }
    else {
      val extendedArgs = anyRefArgs :+ throwable
      '{
        if ($underlying.delegate.isEnabled($level, $marker))
          $underlying.delegate.log($level, $marker, $message.toString, ${ Expr.ofSeq(extendedArgs) }*)
      }
    }
  }

  /** Checks whether `message` is an interpolated string and transforms it into LOG4J string interpolation. */
  private[slf4j] def deconstructInterpolatedMessage(message: Expr[CharSequence])(
      using Quotes
  ): (Expr[CharSequence], Seq[Expr[Any]]) = {
    import quotes.reflect.*
    import util.*

    message.asTerm match {
      case Inlined(_, _, Apply(Select(Apply(Select(Select(_, "StringContext"), _), messageNode), _), argumentsNode)) =>
        val messageTextPartsOpt: Option[List[String]] =
          messageNode.collectFirst { case Typed(Repeated(ls, _), _) =>
            ls.collect { case Literal(StringConstant(s)) => s }
          }
        val argsOpt: Option[List[Term]] =
          argumentsNode.collectFirst { case Typed(Repeated(ls, _), _) =>
            ls
          }

        (messageTextPartsOpt, argsOpt) match {
          case (Some(messageTextParts), Some(args)) =>
            val format = messageTextParts.iterator
              // Emulate standard interpolator escaping
              .map(StringContext.processEscapes)
              // Escape literal log4j format anchors if the resulting call will require a format string
              .map(str => if (args.nonEmpty) str.replace("{}", "\\{}") else str)
              .mkString("{}")

            val formatArgs = args.map(_.asExpr)

            (Expr(format), formatArgs)
          case _ =>
            (message, Seq.empty)
        }
      case _ => (message, Seq.empty)
    }
  }

  private[slf4j] def formatArgs(args: Expr[Seq[Any]])(
      using q: Quotes
  ): Seq[Expr[Object]] = {
    import quotes.reflect.*
    import util.*

    args.asTerm match {
      case p @ Inlined(_, _, Typed(Repeated(v, _), _)) =>
        v.map {
          case t if t.tpe <:< TypeRepr.of[AnyRef] => t.asExprOf[Object]
          case t                                  => '{ ${ t.asExpr }.asInstanceOf[Object] }
        }
      case Repeated(v, _) =>
        v.map {
          case t if t.tpe <:< TypeRepr.of[Object] => t.asExprOf[Object]
          case t                                  => '{ ${ t.asExpr }.asInstanceOf[Object] }
        }
      case _ => Seq.empty
    }
  }

}
