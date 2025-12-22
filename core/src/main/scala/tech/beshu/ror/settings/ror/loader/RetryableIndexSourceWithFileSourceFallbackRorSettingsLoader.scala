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
package tech.beshu.ror.settings.ror.loader

import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.implicits.*
import tech.beshu.ror.settings.ror.source.*
import tech.beshu.ror.settings.ror.{MainRorSettings, TestRorSettings}

class RetryableIndexSourceWithFileSourceFallbackRorSettingsLoader(mainSettingsIndexSource: MainSettingsIndexSource,
                                                                  mainSettingsIndexLoadingRetryStrategy: RetryStrategy,
                                                                  mainSettingsFileSource: MainSettingsFileSource,
                                                                  testSettingsIndexSource: TestSettingsIndexSource)
  extends StartingRorSettingsLoader with Logging {

  override def load(): Task[Either[LoadingError, (MainRorSettings, Option[TestRorSettings])]] = {
    val result = for {
      mainSettings <- mainSettingsIndexLoadingRetryStrategy
        .withRetryT(
          operation = loadMainSettingsFromIndex(),
          operationDescription = s"Loading ReadonlyREST main settings from index '${mainSettingsIndexSource.settingsIndex.show}'"
        )
        .orElse(loadMainSettingsFromFile())
      testSettings <- loadTestSettingsFromIndex()
        .map(Option.apply)
        .recover { case _ => Option.empty[TestRorSettings] }
    } yield (mainSettings, testSettings)
    result.value
  }

  private def loadMainSettingsFromIndex() = {
    loadSettingsFromSource(
      source = mainSettingsIndexSource,
      settingsDescription = s"main settings from index '${mainSettingsIndexSource.settingsIndex.show}'"
    )
  }

  private def loadMainSettingsFromFile() = {
    loadSettingsFromSource(
      source = mainSettingsFileSource,
      settingsDescription = s"main settings from file '${mainSettingsFileSource.settingsFile.show}''"
    )
  }

  private def loadTestSettingsFromIndex() = {
    loadSettingsFromSource(
      source = testSettingsIndexSource,
      settingsDescription = s"test settings from index '${testSettingsIndexSource.settingsIndex.show}'"
    )
  }

}
