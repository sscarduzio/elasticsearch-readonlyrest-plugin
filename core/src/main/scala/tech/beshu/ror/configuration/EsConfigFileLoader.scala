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
import cats.Show
import cats.data.NonEmptyList
import cats.implicits._
import io.circe.Decoder
import tech.beshu.ror.accesscontrol.factory.JsonConfigStaticVariableResolver
import tech.beshu.ror.accesscontrol.factory.JsonConfigStaticVariableResolver.ResolvingError
import tech.beshu.ror.providers.EnvVarsProvider
import tech.beshu.ror.utils.yaml

final class EsConfigFileLoader[CONFIG: Decoder]()(implicit envVarsProvider: EnvVarsProvider) {

  def loadConfigFromFile(file: File,
                         configDescription: String): Either[MalformedSettings, CONFIG] = {
    file.fileReader { reader =>
      yaml
        .parser
        .parse(reader)
        .left.map(e => MalformedSettings(s"Cannot parse file ${file.pathAsString} content. Cause: ${e.message}"))
        .right
        .flatMap { json =>
          JsonConfigStaticVariableResolver.resolve(json)
            .left.map(e => MalformedSettings(show"""Invalid $configDescription configuration. $e."""))
        }
        .flatMap { json =>
          implicitly[Decoder[CONFIG]]
            .decodeJson(json)
            .left.map(_ => MalformedSettings(s"Invalid $configDescription configuration"))
        }
    }
  }

  private implicit val showResolvingError: Show[NonEmptyList[ResolvingError]] =
    Show.show { nel =>
      nel.map(_.msg).mkString_("\n\t* ")
    }
}

final case class MalformedSettings(message: String)