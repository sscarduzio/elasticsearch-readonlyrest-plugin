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

import monix.eval.Task
import tech.beshu.ror.configuration.EsConfig.RorEsLevelSettings.{LoadFromFileSettings, LoadFromIndexSettings}
import tech.beshu.ror.configuration.RawRorConfig
import tech.beshu.ror.configuration.RorConfigLoading.*
import tech.beshu.ror.configuration.RorProperties.{LoadingAttemptsCount, LoadingDelay}
import tech.beshu.ror.configuration.index.IndexConfigManager

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

object LoadRawRorConfig {

  def loadFromIndexWithFileFallback(indexLoadingSettings: LoadFromIndexSettings,
                                    fallbackFileLoadingSettings: LoadFromFileSettings,
                                    indexConfigManager: IndexConfigManager): Task[Either[LoadedRorConfig.Error, LoadedRorConfig[RawRorConfig]]] = {
    attemptLoadingConfigFromIndex(
      settings = indexLoadingSettings,
      fallback = loadRorConfigFromFile(fallbackFileLoadingSettings),
      indexConfigManager
    )
  }

  def loadFromFile(settings: LoadFromFileSettings): Task[Either[LoadedRorConfig.Error, LoadedRorConfig[RawRorConfig]]] = {
    forceLoadRorConfigFromFile(settings)
  }

  def loadFromIndex(settings: LoadFromIndexSettings, indexConfigManager: IndexConfigManager): Task[Either[LoadedRorConfig.Error, LoadedRorConfig[RawRorConfig]]] = {
    for {
      // todo: is the copy ok?
      result <- loadRorConfigFromIndex(
        settings.copy(loadingDelay = LoadingDelay.unsafeFrom(0 seconds)),
        indexConfigManager
      )
      rawRorConfig <- result match {
        case Left(LoadedRorConfig.IndexNotExist) =>
          Task.now(Left(LoadedRorConfig.IndexNotExist: LoadedRorConfig.Error))
        case Left(LoadedRorConfig.IndexUnknownStructure) =>
          Task.now(Left(LoadedRorConfig.IndexUnknownStructure: LoadedRorConfig.Error))
        case Left(error@LoadedRorConfig.IndexParsingError(_)) =>
          Task.now(Left(error: LoadedRorConfig.Error))
        case Right(value) =>
          Task.now(Right(value))
      }
    } yield rawRorConfig
  }

  private def attemptLoadingConfigFromIndex(settings: LoadFromIndexSettings,
                                            fallback: Task[Either[LoadedRorConfig.Error, LoadedRorConfig[RawRorConfig]]],
                                            indexConfigManager: IndexConfigManager): Task[Either[LoadedRorConfig.Error, LoadedRorConfig[RawRorConfig]]] = {
    settings.loadingAttemptsCount.value.value match {
      case 0 =>
        fallback.map(identity)
      case attemptsCount =>
        for {
          result <- loadRorConfigFromIndex(settings, indexConfigManager)
          rawRorConfig <- result match {
            case Left(LoadedRorConfig.IndexNotExist) =>
              attemptLoadingConfigFromIndex(
                settings.copy(loadingAttemptsCount = LoadingAttemptsCount.unsafeFrom(settings.loadingAttemptsCount.value.value - 1)),
                fallback = fallback,
                indexConfigManager
              )
            case Left(LoadedRorConfig.IndexUnknownStructure) =>
              Task.now(Left(LoadedRorConfig.IndexUnknownStructure))
            case Left(error@LoadedRorConfig.IndexParsingError(_)) =>
              Task.now(Left(error))
            case Right(value) =>
              Task.now(Right(value))
          }
        } yield rawRorConfig
    }
  }
}
