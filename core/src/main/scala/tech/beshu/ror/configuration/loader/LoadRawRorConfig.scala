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

import cats.free.Free
import tech.beshu.ror.accesscontrol.domain.RorConfigurationIndex
import tech.beshu.ror.configuration.ConfigLoading.*
import tech.beshu.ror.configuration.loader.LoadedRorConfig.FileConfig
import tech.beshu.ror.configuration.{EsConfig, RawRorConfig}
import tech.beshu.ror.es.EsEnv

object LoadRawRorConfig {

  type LoadResult = ErrorOr[LoadedRorConfig[RawRorConfig]]

  def load(env: EsEnv,
           esConfig: EsConfig,
           configurationIndex: RorConfigurationIndex): LoadRorConfig[LoadResult] = {
    LoadRawRorConfig.load(
      isLoadingFromFileForced = esConfig.rorEsLevelSettings.forceLoadRorFromFile,
      env = env,
      configIndex = configurationIndex,
      indexLoadingAttempts = 5,
    )
  }

  def load(isLoadingFromFileForced: Boolean,
           env: EsEnv,
           configIndex: RorConfigurationIndex,
           indexLoadingAttempts: Int): LoadRorConfig[LoadResult] = {
    for {
      loadedFileOrIndex <- if (isLoadingFromFileForced) {
        forceLoadRorConfigFromFile(env.configPath)
      } else {
        attemptLoadingConfigFromIndex(configIndex, indexLoadingAttempts, loadRorConfigFromFile(env.configPath))
      }
    } yield loadedFileOrIndex
  }

  def attemptLoadingConfigFromIndex(index: RorConfigurationIndex,
                                    attempts: Int,
                                    fallback: LoadRorConfig[ErrorOr[FileConfig[RawRorConfig]]]): LoadRorConfig[LoadResult] = {
    if (attempts <= 0) {
      fallback.map(identity)
    } else {
      for {
        result <- loadRorConfigFromIndex(index)
        rawRorConfig <- result match {
          case Left(LoadedRorConfig.IndexNotExist) =>
            Free.defer(attemptLoadingConfigFromIndex(index, attempts - 1, fallback))
          case Left(LoadedRorConfig.IndexUnknownStructure) =>
            Free.pure[LoadConfigAction, LoadResult](Left(LoadedRorConfig.IndexUnknownStructure))
          case Left(error@LoadedRorConfig.IndexParsingError(_)) =>
            Free.pure[LoadConfigAction, LoadResult](Left(error))
          case Right(value) =>
            Free.pure[LoadConfigAction, LoadResult](Right(value))
        }
      } yield rawRorConfig
    }
  }
}
