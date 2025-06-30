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
import tech.beshu.ror.configuration.EsConfig.RorEsLevelSettings.{LoadFromFileSettings, LoadFromIndexSettings}
import tech.beshu.ror.configuration.RawRorConfig
import tech.beshu.ror.configuration.RorProperties.{LoadingAttemptsCount, LoadingAttemptsInterval}
import tech.beshu.ror.configuration.loader.LoadedRorConfig.FileConfig
import tech.beshu.ror.utils.DurationOps.{NonNegativeFiniteDuration, RefinedDurationOps}

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

object LoadRawRorConfig {

  type LoadResult = ErrorOr[LoadedRorConfig[RawRorConfig]]

  def loadFromIndexWithFileFallback(indexLoadingSettings: LoadFromIndexSettings,
                                    fallbackFileLoadingSettings: LoadFromFileSettings): LoadRorConfig[LoadResult] = {
    attemptLoadingConfigFromIndex(
      index = indexLoadingSettings.rorConfigIndex,
      currentDelay = indexLoadingSettings.loadingDelay.value,
      attemptsCount = indexLoadingSettings.loadingAttemptsCount,
      attemptsInterval = indexLoadingSettings.loadingAttemptsInterval,
      fallback = loadRorConfigFromFile(fallbackFileLoadingSettings)
    )
  }

  def loadFromFile(settings: LoadFromFileSettings): LoadRorConfig[LoadResult] = {
    for {
      loadedConfig <- forceLoadRorConfigFromFile(settings)
    } yield loadedConfig
  }

  def loadFromIndex(configurationIndex: RorConfigurationIndex): LoadRorConfig[LoadResult] = {
    for {
      result <- loadRorConfigFromIndex(configurationIndex, loadingDelay = (0 seconds).toRefinedNonNegativeUnsafe)
      rawRorConfig <- result match {
        case Left(LoadedRorConfig.IndexNotExist) =>
          Free.pure[LoadConfigAction, LoadResult](Left(LoadedRorConfig.IndexNotExist))
        case Left(LoadedRorConfig.IndexUnknownStructure) =>
          Free.pure[LoadConfigAction, LoadResult](Left(LoadedRorConfig.IndexUnknownStructure))
        case Left(error@LoadedRorConfig.IndexParsingError(_)) =>
          Free.pure[LoadConfigAction, LoadResult](Left(error))
        case Right(value) =>
          Free.pure[LoadConfigAction, LoadResult](Right(value))
      }
    } yield rawRorConfig
  }

  private def attemptLoadingConfigFromIndex(index: RorConfigurationIndex,
                                            currentDelay: NonNegativeFiniteDuration,
                                            attemptsCount: LoadingAttemptsCount,
                                            attemptsInterval: LoadingAttemptsInterval,
                                            fallback: LoadRorConfig[ErrorOr[FileConfig[RawRorConfig]]]): LoadRorConfig[LoadResult] = {
    attemptsCount.value.value match {
      case 0 =>
        fallback.map(identity)
      case attemptsCount =>
        for {
          result <- loadRorConfigFromIndex(index, loadingDelay = currentDelay)
          rawRorConfig <- result match {
            case Left(LoadedRorConfig.IndexNotExist) =>
              Free.defer(attemptLoadingConfigFromIndex(
                index = index,
                currentDelay = attemptsInterval.value,
                attemptsCount = LoadingAttemptsCount.unsafeFrom(attemptsCount - 1),
                attemptsInterval = attemptsInterval,
                fallback = fallback
              ))
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
