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
import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.implicits.*
import tech.beshu.ror.settings.ror.source.*
import tech.beshu.ror.settings.ror.source.ReadOnlySettingsSource.SettingsLoadingError
import tech.beshu.ror.settings.ror.{MainRorSettings, TestRorSettings}
import tech.beshu.ror.utils.RequestIdAwareLogging

class RetryableIndexSourceWithFileSourceFallbackRorSettingsLoader(
    mainSettingsIndexSource: MainSettingsIndexSource,
    mainSettingsIndexLoadingRetryStrategy: RetryStrategy,
    mainSettingsFileSource: MainSettingsFileSource,
    testSettingsIndexSource: TestSettingsIndexSource
) extends StartingRorSettingsLoader
    with RequestIdAwareLogging {

  override def load()(
      implicit requestId: RequestId
  ): Task[Either[LoadingError, (MainRorSettings, Option[TestRorSettings])]] = {
    val result = for {
      mainSettings <- loadMainSettings()
      testSettings <- loadTestSettings()
    } yield (mainSettings, testSettings)
    result.value
  }

  private def loadMainSettings()(
      implicit requestId: RequestId
  ): EitherT[Task, LoadingError, MainRorSettings] = {
    mainSettingsIndexLoadingRetryStrategy
      .withRetryT(
        operation = loadMainSettingsFromIndex(),
        operationDescription =
          s"Loading ReadonlyREST main settings from index '${mainSettingsIndexSource.settingsIndex.show}'"
      )
      .leftFlatMap {
        case SettingsLoadingError.SourceSpecificError(IndexSettingsSource.LoadingError.DocumentUnreachable) =>
          cannotReadExistingIndexSettings
        case _ =>
          loadMainSettingsFromFile().leftMap(_.show)
      }
  }

  private def cannotReadExistingIndexSettings[T](
      implicit requestId: RequestId
  ) = {
    val error = s"Cannot read ReadonlyREST settings from index '${mainSettingsIndexSource.settingsIndex.show}'. " +
      s"Settings from file '${mainSettingsFileSource.settingsFile.show}' will NOT be used as a fallback, " +
      s"because they could differ from the settings used by the rest of the cluster."
    EitherT
      .liftF[Task, LoadingError, Unit](logger.dError(error))
      .flatMap(_ => EitherT.leftT[Task, T](error))
  }

  private def loadTestSettings()(
      implicit requestId: RequestId
  ): EitherT[Task, LoadingError, Option[TestRorSettings]] = {
    loadTestSettingsFromIndex()
      .map(Option.apply)
      .recover { case _ => Option.empty[TestRorSettings] }
      .leftMap(_.show)
  }

  private def loadMainSettingsFromIndex()(
      implicit requestId: RequestId
  ) = {
    loadSettingsFromSource(
      source = mainSettingsIndexSource,
      settingsDescription = s"main settings from index '${mainSettingsIndexSource.settingsIndex.show}'"
    )
  }

  private def loadMainSettingsFromFile()(
      implicit requestId: RequestId
  ) = {
    loadSettingsFromSource(
      source = mainSettingsFileSource,
      settingsDescription = s"main settings from file '${mainSettingsFileSource.settingsFile.show}''"
    )
  }

  private def loadTestSettingsFromIndex()(
      implicit requestId: RequestId
  ) = {
    loadSettingsFromSource(
      source = testSettingsIndexSource,
      settingsDescription = s"test settings from index '${testSettingsIndexSource.settingsIndex.show}'"
    )
  }

}
