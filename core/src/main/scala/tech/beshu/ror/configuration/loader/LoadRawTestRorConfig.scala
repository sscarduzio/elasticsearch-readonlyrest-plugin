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
import tech.beshu.ror.configuration.RorProperties.{LoadingAttemptsCount, LoadingAttemptsInterval, LoadingDelay}
import tech.beshu.ror.configuration.TestConfigLoading.*
import tech.beshu.ror.configuration.TestRorConfig
import tech.beshu.ror.utils.DurationOps.PositiveFiniteDuration

object LoadRawTestRorConfig {

  def loadFromIndexWithFallback(configurationIndex: RorConfigurationIndex,
                                loadingDelay: LoadingDelay,
                                indexLoadingAttemptsCount: LoadingAttemptsCount,
                                indexLoadingAttemptsInterval: LoadingAttemptsInterval,
                                fallbackConfig: TestRorConfig): LoadTestRorConfig[IndexErrorOr[LoadedTestRorConfig[TestRorConfig]]] = {
    attemptLoadingConfigFromIndex(
      index = configurationIndex,
      currentDelay = None,
      delay = loadingDelay,
      attemptsCount = indexLoadingAttemptsCount,
      attemptsInterval = indexLoadingAttemptsInterval,
      fallback = fallbackConfig
    )
  }

  private def attemptLoadingConfigFromIndex(index: RorConfigurationIndex,
                                            currentDelay: Option[PositiveFiniteDuration],
                                            delay: LoadingDelay,
                                            attemptsCount: LoadingAttemptsCount,
                                            attemptsInterval: LoadingAttemptsInterval,
                                            fallback: TestRorConfig): LoadTestRorConfig[IndexErrorOr[LoadedTestRorConfig[TestRorConfig]]] = {
    attemptsCount.value.value match {
      case 0 =>
        Free.pure[LoadTestConfigAction, IndexErrorOr[LoadedTestRorConfig[TestRorConfig]]](
          Right(LoadedTestRorConfig.FallbackConfig[TestRorConfig](fallback))
        )
      case attemptsCount =>
        for {
          result <- loadRorConfigFromIndex(index, loadingDelay = currentDelay)
          rawRorConfig <- result match {
            case Left(LoadedTestRorConfig.IndexNotExist) =>
              Free.defer(attemptLoadingConfigFromIndex(
                index = index,
                currentDelay = Some(delay.duration),
                delay = delay,
                attemptsCount = LoadingAttemptsCount.unsafeFrom(attemptsCount - 1),
                attemptsInterval = attemptsInterval,
                fallback = fallback
              ))
            case Left(error@LoadedTestRorConfig.IndexUnknownStructure) =>
              Free.pure[LoadTestConfigAction, IndexErrorOr[LoadedTestRorConfig[TestRorConfig]]](Left(error))
            case Left(error@LoadedTestRorConfig.IndexParsingError(_)) =>
              Free.pure[LoadTestConfigAction, IndexErrorOr[LoadedTestRorConfig[TestRorConfig]]](Left(error))
            case Right(value) =>
              Free.pure[LoadTestConfigAction, IndexErrorOr[LoadedTestRorConfig[TestRorConfig]]](Right(value))
          }
        } yield rawRorConfig
    }
  }
}
