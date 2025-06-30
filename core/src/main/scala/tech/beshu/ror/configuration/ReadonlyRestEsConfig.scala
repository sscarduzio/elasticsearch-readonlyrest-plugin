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

import cats.data.EitherT
import monix.eval.Task
import tech.beshu.ror.SystemContext
import tech.beshu.ror.configuration.EsConfig.RorEsLevelSettings.LoadFromFileSettings
import tech.beshu.ror.es.EsEnv
import tech.beshu.ror.utils.ScalaOps.*

final case class ReadonlyRestEsConfig(bootConfig: RorBootConfiguration,
                                      sslConfig: RorSsl,
                                      fipsConfig: FipsConfiguration)

object ReadonlyRestEsConfig {

  // todo: do we need it?
  def load(esEnv: EsEnv, rorFileSettings: LoadFromFileSettings)
          (implicit systemContext: SystemContext): Task[Either[MalformedSettings, ReadonlyRestEsConfig]] = {
    value {
      for {
        bootConfig <- EitherT(RorBootConfiguration.load(esEnv))
        sslConfig <- EitherT(RorSsl.load(esEnv, rorFileSettings))
        fipsConfig <- EitherT(FipsConfiguration.load(esEnv, rorFileSettings))
      } yield ReadonlyRestEsConfig(bootConfig, sslConfig, fipsConfig)
    }
  }
}
