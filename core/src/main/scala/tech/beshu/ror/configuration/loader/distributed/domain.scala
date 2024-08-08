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
package tech.beshu.ror.configuration.loader.distributed

import tech.beshu.ror.configuration.loader.LoadedRorConfig

import scala.concurrent.duration._
import scala.language.postfixOps

final case class NodeConfig(loadedConfig: Either[LoadedRorConfig.Error, LoadedRorConfig[String]])
final case class Timeout(nanos: Long) extends AnyVal
final case class NodeConfigRequest(timeout: Timeout)
object NodeConfigRequest {
  val defaultTimeout: Timeout = Timeout(nanos = (10 seconds).toNanos)
}