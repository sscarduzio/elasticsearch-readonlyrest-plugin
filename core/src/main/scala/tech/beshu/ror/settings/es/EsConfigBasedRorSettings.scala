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
import tech.beshu.ror.es.EsEnv
import tech.beshu.ror.settings.es.EsConfigBasedRorSettings.LoadingError.{FileNotFound, MalformedContent}

import scala.language.{implicitConversions, postfixOps}

final case class EsConfigBasedRorSettings(settingsSource: RorSettingsSourcesConfig,
                                          boot: RorBootSettings,
                                          ssl: Option[RorSslSettings],
                                          loadingRorCoreStrategy: LoadingRorCoreStrategySettings)

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
      settingsSource <- RorSettingsSourcesConfig.from(esEnv).adaptError
      bootSettings <- RorBootSettings.load(esEnv).adaptError
      sslSettings <- RorSslSettings.load(esEnv, settingsSource.settingsFile).adaptError
      loadingRorCoreStrategy <- LoadingRorCoreStrategySettings.load(esEnv).adaptError
    } yield EsConfigBasedRorSettings(settingsSource, bootSettings, sslSettings, loadingRorCoreStrategy)
    result.value
  }

  private implicit class AdaptError[T](val task: Task[Either[MalformedSettings, T]]) extends AnyVal {
    def adaptError: EitherT[Task, LoadingError, T] =
      EitherT(task).leftMap(error => MalformedContent(error.file, error.message): LoadingError)
  }

}
