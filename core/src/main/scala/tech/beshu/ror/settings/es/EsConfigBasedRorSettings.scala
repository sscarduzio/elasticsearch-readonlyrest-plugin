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
import tech.beshu.ror.implicits.*
import tech.beshu.ror.settings.es.ElasticsearchConfigLoader.LoadingError
import tech.beshu.ror.utils.RequestIdAwareLogging

import scala.language.{implicitConversions, postfixOps}

final case class EsConfigBasedRorSettings(settingsSource: RorSettingsSourcesConfig,
                                          boot: RorBootSettings,
                                          ssl: Option[RorSslSettings],
                                          rorCoreSettingsLoadingStrategy: RorCoreSettingsLoadingStrategy)

object EsConfigBasedRorSettings extends RequestIdAwareLogging {

  def from(esEnv: EsEnv)
          (implicit systemContext: SystemContext): Task[Either[LoadingError, EsConfigBasedRorSettings]] = {
    for {
     _ <- Task.delay(noRequestIdLogger.info("Loading ROR node settings ..."))
     result <- doLoad(esEnv).value
     _ <- result match {
       case Right(settings) => Task.delay(noRequestIdLogger.debug(s"Loaded ROR node settings:\n${settings.show}"))
       case Left(_) => Task.unit
     }
    } yield result
  }

  private def doLoad(esEnv: EsEnv)
                    (implicit systemContext: SystemContext) = {
    for {
      settingsSource <- EitherT(RorSettingsSourcesConfig.from(esEnv))
      bootSettings <- EitherT(RorBootSettings.load(esEnv))
      sslSettings <- EitherT(RorSslSettings.load(esEnv, settingsSource.settingsFile))
      loadingRorCoreStrategy <- EitherT(RorCoreSettingsLoadingStrategy.load(esEnv))
    } yield EsConfigBasedRorSettings(settingsSource, bootSettings, sslSettings, loadingRorCoreStrategy)
  }
}
