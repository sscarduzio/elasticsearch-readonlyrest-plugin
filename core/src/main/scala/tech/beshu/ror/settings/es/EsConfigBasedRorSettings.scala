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
package tech.beshu.ror.settings.es

import cats.data.EitherT
import monix.eval.Task
import tech.beshu.ror.SystemContext
import tech.beshu.ror.es.EsEnv
import tech.beshu.ror.settings.es.YamlFileBasedSettingsLoader.LoadingError

import scala.language.{implicitConversions, postfixOps}

final case class EsConfigBasedRorSettings(settingsSource: RorSettingsSourcesConfig,
                                          boot: RorBootSettings,
                                          ssl: Option[RorSslSettings],
                                          loadingRorCoreStrategy: LoadingRorCoreStrategySettings)

object EsConfigBasedRorSettings {

  def from(esEnv: EsEnv)
          (implicit systemContext: SystemContext): Task[Either[LoadingError, EsConfigBasedRorSettings]] = {
    val result = for {
      settingsSource <- EitherT(RorSettingsSourcesConfig.from(esEnv))
      bootSettings <- EitherT(RorBootSettings.load(esEnv))
      sslSettings <- EitherT(RorSslSettings.load(esEnv, settingsSource.settingsFile))
      loadingRorCoreStrategy <- EitherT(LoadingRorCoreStrategySettings.load(esEnv))
    } yield EsConfigBasedRorSettings(settingsSource, bootSettings, sslSettings, loadingRorCoreStrategy)
    result.value
  }
}
