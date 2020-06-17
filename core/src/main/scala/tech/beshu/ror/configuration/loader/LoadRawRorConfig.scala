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
import tech.beshu.ror.configuration.ConfigLoading._
import tech.beshu.ror.configuration.{EsConfig, RawRorConfig}

import scala.language.implicitConversions


object LoadRawRorConfig {
  def load(esConfigPath: Path,
           esConfig: EsConfig,
           configurationIndex: RorConfigurationIndex): Load[ErrorOr[LoadedConfig[RawRorConfig]]] = {
    LoadRawRorConfig.load(
      isLoadingFromFileForced = esConfig.rorEsLevelSettings.forceLoadRorFromFile,
      esConfigPath = esConfigPath,
      configIndex = configurationIndex,
      indexLoadingAttempts = 5,
    )
  }

  def load(isLoadingFromFileForced: Boolean,
           esConfigPath: Path,
           configIndex: RorConfigurationIndex,
           indexLoadingAttempts: Int): Load[ErrorOr[LoadedConfig[RawRorConfig]]] = {
    for {
      loadedFileOrIndex <- if (isLoadingFromFileForced) {
        forceLoadFromFile(esConfigPath)
      } else {
        attemptLoadingConfigFromIndex(configIndex, indexLoadingAttempts, recoverIndexWithFile(esConfigPath, _))
      }
    } yield loadedFileOrIndex
  }

  def attemptLoadingConfigFromIndex(index: RorConfigurationIndex,
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
