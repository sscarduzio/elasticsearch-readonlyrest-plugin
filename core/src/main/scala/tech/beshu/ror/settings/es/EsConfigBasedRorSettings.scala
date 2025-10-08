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

import better.files.File
import cats.data.EitherT
import monix.eval.Task
import tech.beshu.ror.SystemContext
import tech.beshu.ror.accesscontrol.domain.RorSettingsFile
import tech.beshu.ror.es.EsEnv
import tech.beshu.ror.settings.es.EsConfigBasedRorSettings.LoadingError.{FileNotFound, MalformedContent}

import scala.language.{implicitConversions, postfixOps}

final case class EsConfigBasedRorSettings(settingsSource: RorSettingsSourcesConfig,
                                          boot: RorBootSettings,
                                          ssl: Option[RorSslSettings],
                                          loadingRorCoreStrategy: LoadingRorCoreStrategy)

object EsConfigBasedRorSettings {

  sealed trait LoadingError
  object LoadingError {
    final case class FileNotFound(file: File) extends LoadingError
    final case class MalformedContent(file: File, message: String) extends LoadingError
  }

  def from(esEnv: EsEnv)
          (implicit systemContext: SystemContext): Task[Either[LoadingError, EsConfigBasedRorSettings]] = {
    val esConfig = esEnv.elasticsearchConfig
    val result = for {
      _ <- EitherT.fromEither[Task](Either.cond(esConfig.file.exists, (), FileNotFound(esConfig.file): LoadingError))
      settingsSource <- loadSettingsSource(esEnv)
      bootSettings <- loadRorBootSettings(esEnv)
      sslSettings <- loadSslSettings(esEnv, settingsSource.settingsFile)
      loadingRorCoreStrategy <- loadLoadingRorCoreStrategy(esEnv)
    } yield EsConfigBasedRorSettings(settingsSource, bootSettings, sslSettings, loadingRorCoreStrategy)
    result.value
  }

  private def loadRorBootSettings(esEnv: EsEnv)
                                 (implicit systemContext: SystemContext): EitherT[Task, LoadingError, RorBootSettings] = {
    EitherT(RorBootSettings.load(esEnv))
      .leftMap(error => MalformedContent(esEnv.elasticsearchConfig.file, error.message))
  }

  private def loadLoadingRorCoreStrategy(esEnv: EsEnv)
                                        (implicit systemContext: SystemContext): EitherT[Task, LoadingError, LoadingRorCoreStrategy] = {
    EitherT(LoadingRorCoreStrategy.load(esEnv))
      .leftMap(error => MalformedContent(esEnv.elasticsearchConfig.file, error.message))
  }

  private def loadSettingsSource(esEnv: EsEnv)
                                (implicit systemContext: SystemContext): EitherT[Task, LoadingError, RorSettingsSourcesConfig] = {
    EitherT(RorSettingsSourcesConfig.from(esEnv))
      .leftMap(error => MalformedContent(esEnv.elasticsearchConfig.file, error.message))
  }

  private def loadSslSettings(esEnv: EsEnv, rorSettingsFile: RorSettingsFile)
                             (implicit systemContext: SystemContext): EitherT[Task, LoadingError, Option[RorSslSettings]] = {
    EitherT(RorSslSettings.load(esEnv, rorSettingsFile))
      .leftMap(error => MalformedContent(esEnv.elasticsearchConfig.file, error.message))
  }

}
