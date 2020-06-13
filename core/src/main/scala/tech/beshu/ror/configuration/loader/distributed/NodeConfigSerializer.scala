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

import io.circe.Codec
import tech.beshu.ror.configuration.loader.LoadedConfig

object NodeConfigSerializer {

  import io.circe.parser
  import io.circe.syntax._

  implicit private val codecNodeConfig: Codec[NodeConfig] = {
    val codec = Codec.codecForEither[LoadedConfig.Error, LoadedConfig[String]]("error", "config")
    Codec.from(codec.map(NodeConfig), codec.contramap(_.loadedConfig))
  }

  def serialize(nodeConfig: NodeConfig): String = {
    nodeConfig.asJson.noSpaces
  }

  def parse(str: String): NodeConfig = {
    parser.decode[NodeConfig](str).toTry.get
  }
}
