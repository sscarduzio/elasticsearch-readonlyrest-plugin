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
import tech.beshu.ror.configuration.TestConfigLoading._
import tech.beshu.ror.configuration.TestRorConfig
import scala.language.implicitConversions

object LoadRawTestRorConfig {
  def load(configurationIndex: RorConfigurationIndex): LoadTestRorConfig[ErrorOr[LoadedTestRorConfig[TestRorConfig]]] = {
    LoadRawTestRorConfig.load(
      configIndex = configurationIndex,
      indexLoadingAttempts = 5,
    )
  }

  def load(configIndex: RorConfigurationIndex,
           indexLoadingAttempts: Int): LoadTestRorConfig[ErrorOr[LoadedTestRorConfig[TestRorConfig]]] = {
    attemptLoadingConfigFromIndex(configIndex, indexLoadingAttempts)
  }

  def attemptLoadingConfigFromIndex(index: RorConfigurationIndex,
                                    attempts: Int): LoadTestRorConfig[ErrorOr[LoadedTestRorConfig[TestRorConfig]]] = {
    if (attempts <= 0) {
      Free.pure[LoadTestConfigAction, ErrorOr[LoadedTestRorConfig[TestRorConfig]]](
        Right(LoadedTestRorConfig.FallbackConfig[TestRorConfig](TestRorConfig.NotSet))
      )
    } else {
      for {
        result <- loadRorConfigFromIndex(index)
        rawRorConfig <- result match {
          case Left(LoadedTestRorConfig.IndexNotExist) =>
            Free.defer(attemptLoadingConfigFromIndex(index, attempts - 1))
          case Left(error@LoadedTestRorConfig.IndexUnknownStructure) =>
            Free.pure[LoadTestConfigAction, ErrorOr[LoadedTestRorConfig[TestRorConfig]]](Left(error))
          case Left(error@LoadedTestRorConfig.IndexParsingError(_)) =>
            Free.pure[LoadTestConfigAction, ErrorOr[LoadedTestRorConfig[TestRorConfig]]](Left(error))
          case Right(value) =>
            Free.pure[LoadTestConfigAction, ErrorOr[LoadedTestRorConfig[TestRorConfig]]](Right(value))
        }
      } yield rawRorConfig
    }
  }
}
