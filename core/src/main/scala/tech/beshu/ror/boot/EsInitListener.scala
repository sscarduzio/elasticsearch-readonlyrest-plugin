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

import monix.eval.Task
import org.apache.logging.log4j.scala.Logging

import scala.concurrent.Promise

class EsInitListener extends Logging {

  private val readyPromise = Promise[Unit]()

  logger.info("ReadonlyREST is waiting for full Elasticsearch init")

  def waitUntilReady: Task[Unit] = Task.fromFuture(readyPromise.future)

  def onEsReady(): Unit = {
    logger.info("Elasticsearch fully initiated. ReadonlyREST can continue ...")
    readyPromise.trySuccess(())
  }
}
