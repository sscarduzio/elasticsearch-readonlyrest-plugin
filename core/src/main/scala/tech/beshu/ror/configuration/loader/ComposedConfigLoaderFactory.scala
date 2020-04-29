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
package tech.beshu.ror.configuration.loader

import java.nio.file.Paths

import cats.data.EitherT
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.configuration.EsConfig.LoadEsConfigError
import tech.beshu.ror.configuration.loader.LoadedConfig.{EsFileMalformed, EsIndexConfigurationMalformed}
import tech.beshu.ror.configuration.{Compiler, EsConfig, FileConfigLoader, IndexConfigManager, LLoader, RawRorConfig, RorIndexNameConfiguration}
import tech.beshu.ror.es.IndexJsonContentManager
import tech.beshu.ror.providers.EnvVarsProvider

import scala.language.implicitConversions

final class ComposedConfigLoaderFactory(esConfigPath: java.nio.file.Path,
                                        indexContentManager: IndexJsonContentManager)
                                       (implicit envVarsProvider: EnvVarsProvider) {

  private type ErrorOr[A] = EitherT[Task, LoadedConfig.Error, A]

  def load(): Task[Either[LoadedConfig.Error, LoadedConfig[RawRorConfig]]] = {
    (for {
      esConfig <- loadEsConfig(esConfigPath)
      indexName <- indexName(esConfigPath)
      loadedConfig <- {
        val a = LLoader.program(esConfig.rorEsLevelSettings.forceLoadRorFromFile, esConfigPath, indexName, 5)
          .foldMap(Compiler.create(indexContentManager))
        EitherT(a)
      }
    } yield loadedConfig).value
  }

  implicit private def toJava(path: tech.beshu.ror.configuration.loader.Path):java.nio.file.Path = Paths.get(path.value)
  implicit private def toDomain(path: java.nio.file.Path):tech.beshu.ror.configuration.loader.Path = tech.beshu.ror.configuration.loader.Path(path.toString)

  private def indexName(esConfigPath: java.nio.file.Path): ErrorOr[IndexName] = {
    for {
      rorIndexNameConfig <- EitherT(RorIndexNameConfiguration.load(esConfigPath)).leftMap(ms => EsIndexConfigurationMalformed(ms.message):LoadedConfig.Error)
      indexConfigManager <- EitherT.pure[Task, LoadedConfig.Error](rorIndexNameConfig.name)
    } yield indexConfigManager
  }

  private def loadEsConfig(esConfigPath: java.nio.file.Path)
                          (implicit envVarsProvider: EnvVarsProvider): ErrorOr[EsConfig] = {
    EitherT {
      EsConfig
        .from(esConfigPath)
        .map(_.left.map {
          case LoadEsConfigError.FileNotFound(file) =>
            EsIndexConfigurationMalformed(file.pathAsString)
          case LoadEsConfigError.MalformedContent(file, msg) =>
            EsFileMalformed(file.toJava.toPath, msg)
        })
    }
  }

}
