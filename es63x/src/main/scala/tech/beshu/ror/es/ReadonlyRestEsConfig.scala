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
package tech.beshu.ror.es

import cats.data.EitherT
import monix.eval.Task
import tech.beshu.ror.configuration.{MalformedSettings, RorBootConfiguration, RorSsl}
import tech.beshu.ror.providers.{EnvVarsProvider, PropertiesProvider}
import tech.beshu.ror.utils.ScalaOps._

import java.nio.file.Path

final case class ReadonlyRestEsConfig(bootConfig: RorBootConfiguration,
                                      sslConfig: RorSsl)

object ReadonlyRestEsConfig {
  def load(esConfigFolderPath: Path)
          (implicit envVarsProvider: EnvVarsProvider,
           propertiesProvider: PropertiesProvider): Task[Either[MalformedSettings, ReadonlyRestEsConfig]] = {
    value {
      for {
        bootConfig <- EitherT(RorBootConfiguration.load(esConfigFolderPath))
        sslConfig <- EitherT(RorSsl.load(esConfigFolderPath))
      } yield ReadonlyRestEsConfig(bootConfig, sslConfig)
    }
  }
}