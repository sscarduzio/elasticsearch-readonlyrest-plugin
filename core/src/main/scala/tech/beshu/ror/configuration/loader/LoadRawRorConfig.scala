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
import tech.beshu.ror.configuration.EsConfig.RorEsLevelSettings.{LoadFromFileSettings, LoadFromIndexSettings}
import tech.beshu.ror.configuration.RawRorConfig
import tech.beshu.ror.configuration.RorConfigLoading.*
import tech.beshu.ror.configuration.RorProperties.{LoadingAttemptsCount, LoadingDelay}

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

object LoadRawRorConfig {

  type LoadResult = ErrorOr[LoadedRorConfig[RawRorConfig]]

  def loadFromIndexWithFileFallback(indexLoadingSettings: LoadFromIndexSettings,
                                    fallbackFileLoadingSettings: LoadFromFileSettings): LoadRorConfig[LoadResult] = {
    attemptLoadingConfigFromIndex(
      settings = indexLoadingSettings,
      fallback = loadRorConfigFromFile(fallbackFileLoadingSettings)
    )
  }

  def loadFromFile(settings: LoadFromFileSettings): LoadRorConfig[LoadResult] = {
    for {
      loadedConfig <- forceLoadRorConfigFromFile(settings)
    } yield loadedConfig
  }

  def loadFromIndex(settings: LoadFromIndexSettings): LoadRorConfig[LoadResult] = {
    for {
      // todo: is the copy ok?
      result <- loadRorConfigFromIndex(settings.copy(loadingDelay = LoadingDelay.unsafeFrom(0 seconds)))
      rawRorConfig <- result match {
        case Left(LoadedRorConfig.IndexNotExist) =>
          Free.pure[LoadRorConfigAction, LoadResult](Left(LoadedRorConfig.IndexNotExist))
        case Left(LoadedRorConfig.IndexUnknownStructure) =>
          Free.pure[LoadRorConfigAction, LoadResult](Left(LoadedRorConfig.IndexUnknownStructure))
        case Left(error@LoadedRorConfig.IndexParsingError(_)) =>
          Free.pure[LoadRorConfigAction, LoadResult](Left(error))
        case Right(value) =>
          Free.pure[LoadRorConfigAction, LoadResult](Right(value))
      }
    } yield rawRorConfig
  }

  private def attemptLoadingConfigFromIndex(settings: LoadFromIndexSettings,
                                            fallback: LoadRorConfig[ErrorOr[LoadedRorConfig[RawRorConfig]]]): LoadRorConfig[LoadResult] = {
    settings.loadingAttemptsCount.value.value match {
      case 0 =>
        fallback.map(identity)
      case attemptsCount =>
        for {
          result <- loadRorConfigFromIndex(settings)
          rawRorConfig <- result match {
            case Left(LoadedRorConfig.IndexNotExist) =>
              Free.defer(attemptLoadingConfigFromIndex(
                settings.copy(loadingAttemptsCount = LoadingAttemptsCount.unsafeFrom(settings.loadingAttemptsCount.value.value - 1)),
                fallback = fallback
              ))
            case Left(LoadedRorConfig.IndexUnknownStructure) =>
              Free.pure[LoadRorConfigAction, LoadResult](Left(LoadedRorConfig.IndexUnknownStructure))
            case Left(error@LoadedRorConfig.IndexParsingError(_)) =>
              Free.pure[LoadRorConfigAction, LoadResult](Left(error))
            case Right(value) =>
              Free.pure[LoadRorConfigAction, LoadResult](Right(value))
          }
        } yield rawRorConfig
    }
  }
}
