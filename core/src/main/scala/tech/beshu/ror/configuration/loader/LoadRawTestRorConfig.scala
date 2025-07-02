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
import tech.beshu.ror.configuration.EsConfig.RorEsLevelSettings.LoadFromIndexSettings
import tech.beshu.ror.configuration.RorProperties.{LoadingAttemptsCount, LoadingDelay}
import tech.beshu.ror.configuration.TestRorConfig
import tech.beshu.ror.configuration.TestRorConfigLoading.*

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

object LoadRawTestRorConfig {

  def loadFromIndexWithFallback(indexLoadingSettings: LoadFromIndexSettings,
                                fallbackConfig: TestRorConfig): LoadTestRorConfig[IndexErrorOr[LoadedTestRorConfig[TestRorConfig]]] = {
    attemptLoadingConfigFromIndex(
      settings = indexLoadingSettings,
      fallback = fallbackConfig
    )
  }

  private def attemptLoadingConfigFromIndex(settings: LoadFromIndexSettings,
                                            fallback: TestRorConfig): LoadTestRorConfig[IndexErrorOr[LoadedTestRorConfig[TestRorConfig]]] = {
    settings.loadingAttemptsCount.value.value match {
      case 0 =>
        Free.pure[LoadRorTestConfigAction, IndexErrorOr[LoadedTestRorConfig[TestRorConfig]]](
          Right(LoadedTestRorConfig[TestRorConfig](fallback))
        )
      case attemptsCount =>
        for {
          result <- loadTestRorConfigFromIndex(settings.copy(loadingDelay = LoadingDelay.unsafeFrom(0 seconds)))
          rawRorConfig <- result match {
            case Left(LoadedTestRorConfig.IndexNotExist) =>
              Free.defer(attemptLoadingConfigFromIndex(
                settings.copy(loadingAttemptsCount = LoadingAttemptsCount.unsafeFrom(settings.loadingAttemptsCount.value.value - 1)),
                fallback = fallback
              ))
            case Left(error@LoadedTestRorConfig.IndexUnknownStructure) =>
              Free.pure[LoadRorTestConfigAction, IndexErrorOr[LoadedTestRorConfig[TestRorConfig]]](Left(error))
            case Left(error@LoadedTestRorConfig.IndexParsingError(_)) =>
              Free.pure[LoadRorTestConfigAction, IndexErrorOr[LoadedTestRorConfig[TestRorConfig]]](Left(error))
            case Right(value) =>
              Free.pure[LoadRorTestConfigAction, IndexErrorOr[LoadedTestRorConfig[TestRorConfig]]](Right(value))
          }
        } yield rawRorConfig
    }
  }
}
