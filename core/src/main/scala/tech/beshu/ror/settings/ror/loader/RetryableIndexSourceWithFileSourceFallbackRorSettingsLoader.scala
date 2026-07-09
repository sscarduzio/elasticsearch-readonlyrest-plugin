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
      testSettings <- loadTestSettingsFromIndex()
        .map(Option.apply)
        .recover { case _ => Option.empty[TestRorSettings] }
        .leftMap(_.show)
    } yield (mainSettings, testSettings)
    result.value
  }

  /**
   * The file settings are a fallback for the case when the index does not hold any ReadonlyREST settings yet. They are
   * NOT a fallback for the case when the settings index cannot be read (eg. there is no master node yet, or the index
   * shards are not allocated yet) - the settings may well be there, and they may differ from the ones in the file.
   * Using the file settings then would make this node enforce an ACL different from the one used by the rest of the
   * cluster, silently. So a failure is reported instead, and it is up to the caller to try again later.
   */
  private def loadMainSettings(): EitherT[Task, LoadingError, MainRorSettings] = {
    mainSettingsIndexLoadingRetryStrategy
      .withRetryT(
        operation = loadMainSettingsFromIndex(),
        operationDescription = s"Loading ReadonlyREST main settings from index '${mainSettingsIndexSource.settingsIndex.show}'"
      )
      .leftFlatMap {
        case SettingsLoadingError.SourceSpecificError(IndexSettingsSource.LoadingError.DocumentUnreachable) =>
          EitherT
            .liftF[Task, LoadingError, Unit](logger.dError(settingsIndexUnreachableError))
            .flatMap(_ => EitherT.leftT[Task, MainRorSettings](settingsIndexUnreachableError))
        case _ =>
          loadMainSettingsFromFile().leftMap(_.show)
      }
  }

  private lazy val settingsIndexUnreachableError: LoadingError = {
    s"Cannot read ReadonlyREST settings from index '${mainSettingsIndexSource.settingsIndex.show}'. " +
      s"Settings from file '${mainSettingsFileSource.settingsFile.show}' will NOT be used as a fallback, " +
      s"because they could differ from the settings used by the rest of the cluster."
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
