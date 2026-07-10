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

import cats.data.EitherT
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.implicits.*
import tech.beshu.ror.settings.ror.source.ReadOnlySettingsSource.SettingsLoadingError
import tech.beshu.ror.settings.ror.source.*
import tech.beshu.ror.settings.ror.{MainRorSettings, TestRorSettings}
import tech.beshu.ror.utils.ScalaOps.LoggerOps

class RetryableIndexSourceWithFileSourceFallbackRorSettingsLoader(mainSettingsIndexSource: MainSettingsIndexSource,
                                                                  mainSettingsIndexLoadingRetryStrategy: RetryStrategy,
                                                                  mainSettingsFileSource: MainSettingsFileSource,
                                                                  testSettingsIndexSource: TestSettingsIndexSource)
  extends StartingRorSettingsLoader with Logging {

  override def load(): Task[Either[LoadingError, (MainRorSettings, Option[TestRorSettings])]] = {
    val result = for {
      mainSettings <- loadMainSettings()
      testSettings <- loadTestSettings()
    } yield (mainSettings, testSettings)
    result.value
  }

  private def loadMainSettings(): EitherT[Task, LoadingError, MainRorSettings] = {
    mainSettingsIndexLoadingRetryStrategy
      .withRetryT(
        operation = loadMainSettingsFromIndex(),
        operationDescription = s"Loading ReadonlyREST main settings from index '${mainSettingsIndexSource.settingsIndex.show}'"
      )
      .leftFlatMap {
        case SettingsLoadingError.SourceSpecificError(
          IndexSettingsSource.LoadingError.IndexNotFound | IndexSettingsSource.LoadingError.DocumentNotFound
        ) =>
          fallbackToFileSettings()
        case SettingsLoadingError.SourceSpecificError(IndexSettingsSource.LoadingError.DocumentUnreachable) =>
          noFallbackToFileSettings(
            s"Cannot read ReadonlyREST settings from index '${mainSettingsIndexSource.settingsIndex.show}'."
          )
        case SettingsLoadingError.SettingsMalformed(cause) =>
          noFallbackToFileSettings(
            s"ReadonlyREST settings found in index '${mainSettingsIndexSource.settingsIndex.show}' are malformed: ${cause.show}."
          )
      }
  }

  private def fallbackToFileSettings(): EitherT[Task, LoadingError, MainRorSettings] = {
    val message = s"No ReadonlyREST settings found in index '${mainSettingsIndexSource.settingsIndex.show}'. " +
      s"Falling back to the settings from file '${mainSettingsFileSource.settingsFile.show}'. " +
      s"The settings from the index will take precedence once they are created."
    EitherT
      .liftF[Task, LoadingError, Unit](logger.dWarn(message))
      .flatMap(_ => loadMainSettingsFromFile().leftMap(_.show))
  }

  private def noFallbackToFileSettings[T](reason: String): EitherT[Task, LoadingError, T] = {
    val error = s"$reason Settings from file '${mainSettingsFileSource.settingsFile.show}' will NOT be used " +
      s"as a fallback, because there is no way to tell whether they are the settings which the rest of the " +
      s"cluster is using."
    EitherT.leftT[Task, T](error)
  }

  private def loadTestSettings(): EitherT[Task, LoadingError, Option[TestRorSettings]] = {
    loadTestSettingsFromIndex()
      .map(Option.apply)
      .recoverWith { case error =>
        EitherT.liftF[Task, IndexSettingsSource.IndexSettingsLoadingError, Option[TestRorSettings]] {
          logger
            .dWarn(
              s"ReadonlyREST test settings could not be loaded from index '${testSettingsIndexSource.settingsIndex.show}': " +
                s"${error.show}. ReadonlyREST will start without them."
            )
            .map(_ => Option.empty[TestRorSettings])
        }
      }
      .leftMap(_.show)
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
