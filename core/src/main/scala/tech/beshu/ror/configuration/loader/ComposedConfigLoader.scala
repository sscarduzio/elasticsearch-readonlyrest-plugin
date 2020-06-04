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
import cats.free.Free
import monix.eval.Task
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.configuration.ConfigLoading._
import tech.beshu.ror.configuration.EsConfig.LoadEsConfigError
import tech.beshu.ror.configuration.loader.LoadedConfig.{EsFileMalformed, EsIndexConfigurationMalformed}
import tech.beshu.ror.configuration.{Compiler, EsConfig, IndexConfigManager, RawRorConfig, RorIndexNameConfiguration}
import tech.beshu.ror.es.IndexJsonContentService
import tech.beshu.ror.providers.EnvVarsProvider

import scala.language.implicitConversions

final class ComposedConfigLoader(esConfigPath: java.nio.file.Path,
                                 indexContentManager: IndexJsonContentService)
                                (implicit envVarsProvider: EnvVarsProvider) {

  private type ErrorOr[A] = EitherT[Task, LoadedConfig.Error, A]

  def load(): Task[Either[LoadedConfig.Error, LoadedConfig[RawRorConfig]]] = {
    (for {
      esConfig <- loadEsConfig(esConfigPath)
      indexName <- indexName(esConfigPath)
      loadedConfig <- loadRowConfig(esConfig, indexName)
    } yield loadedConfig).value
  }

  private def loadRowConfig(esConfig: EsConfig, indexName: IndexName) = {
    val result = ComposedConfigLoader.loadRowConfig(
      isLoadingFromFileForced = esConfig.rorEsLevelSettings.forceLoadRorFromFile,
      configFile = esConfigPath,
      configIndex = indexName,
      indexLoadingAttempts = 5,
    )
      .foldMap(Compiler.create(indexContentManager))
    EitherT(result)
  }

  private def indexName(esConfigPath: java.nio.file.Path): ErrorOr[IndexName] = {
    for {
      rorIndexNameConfig <- EitherT(RorIndexNameConfiguration.load(esConfigPath)).leftMap(ms => EsIndexConfigurationMalformed(ms.message): LoadedConfig.Error)
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
object ComposedConfigLoader {

  def loadRowConfig(isLoadingFromFileForced: Boolean,
                    configFile: Path,
                    configIndex: IndexName,
                    indexLoadingAttempts: Int): Load[ErrorOr[LoadedConfig[RawRorConfig]]] = {
    for {
      loadedFileOrIndex <- if (isLoadingFromFileForced) {
        forceLoadFromFile(configFile)
      } else {
        attemptLoadingConfigFromIndex(configIndex, indexLoadingAttempts, recoverIndexWithFile(configFile, _))
      }
    } yield loadedFileOrIndex
  }

  def attemptLoadingConfigFromIndex(index: IndexName,
                                    attempts: Int,
                                    fallback: Fallback[RawRorConfig]): Load[ErrorOr[LoadedConfig[RawRorConfig]]] = {
    if (attempts <= 1) {
      for {
        result <- loadFromIndex(index)
        rrc <- result match {
          case Left(value) => fallback(value)
          case Right(value) =>
            Free.pure[LoadA, ErrorOr[LoadedConfig[RawRorConfig]]](Right(value))
        }
      } yield rrc
    } else {
      for {
        result <- loadFromIndex(index)
        rrc <- result match {
          case Left(_) => Free.defer(attemptLoadingConfigFromIndex(index, attempts - 1, fallback(_)))
          case Right(value) =>
            Free.pure[LoadA, ErrorOr[LoadedConfig[RawRorConfig]]](Right(value))
        }
      } yield rrc
    }
  }
}
