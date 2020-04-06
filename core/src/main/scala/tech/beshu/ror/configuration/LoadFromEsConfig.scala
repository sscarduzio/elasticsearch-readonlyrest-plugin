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
package tech.beshu.ror.configuration

import better.files.File
import io.circe.Decoder
import tech.beshu.ror.utils.yaml

//todo: to remove?
trait LoadFromEsConfig {

  protected def loadConfigFromFile[CONFIG: Decoder](file: File,
                                                    configName: String): Either[MalformedSettings, CONFIG] = {
    file.fileReader { reader =>
      yaml
        .parser
        .parse(reader)
        .left.map(e => MalformedSettings(s"Cannot parse file ${file.pathAsString} content. Cause: ${e.message}"))
        .right
        .flatMap { json =>
          implicitly[Decoder[CONFIG]]
            .decodeJson(json)
            .left.map(e => MalformedSettings(s"Invalid $configName configuration"))
        }
    }
  }
}


