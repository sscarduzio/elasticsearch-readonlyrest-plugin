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
package tech.beshu.ror.settings.loader

import cats.data.EitherT
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.boot.ReadonlyRest.StartingFailure
import tech.beshu.ror.configuration.{MainRorSettings, TestRorSettings}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.settings.source.*
import tech.beshu.ror.utils.ScalaOps.*

class RetryableIndexSourceWithFileSourceFallbackRorSettingsLoader(mainSettingsIndexSource: MainSettingsIndexSource,
                                                                  mainSettingsIndexLadingRetryStrategy: RetryStrategy,
                                                                  mainSettingsFileSource: MainSettingsFileSource,
                                                                  testSettingsIndexSource: TestSettingsIndexSource)
  extends StartingRorSettingsLoader with Logging {

  override def load(): Task[Either[StartingFailure, (MainRorSettings, Option[TestRorSettings])]] = {
    loadMainSettingsFromIndex().orElse(loadMainSettingsFromFile()).value
    val result = for {
      mainSettings <- mainSettingsIndexLadingRetryStrategy
        .withRetryT(loadMainSettingsFromIndex())
        .orElse(loadMainSettingsFromFile())
      testSettings <- loadTestSettingsFromIndex().recover { case failure => Option.empty[TestRorSettings] }
    } yield (mainSettings, testSettings)
    result.value
  }

  private def loadMainSettingsFromIndex() = {
    for {
      _ <- lift(logger.info(s"Loading ReadonlyREST main settings from index: ${mainSettingsIndexSource.settingsIndex.show} ..."))
      loadedSettings <- EitherT(mainSettingsIndexSource.load())
        .biSemiflatTap(
          error => logger.dInfo(s"Loading ReadonlyREST main settings from index failed: ${error.show}"),
          settings => logger.dDebug(s"Loaded ReadonlyREST main settings from index:\n${settings.rawSettings.rawYaml.show}")
        )
        .leftMap(error => StartingFailure(error.show))
    } yield loadedSettings
  }

  private def loadMainSettingsFromFile() = {
    for {
      _ <- lift(logger.info(s"Loading ReadonlyREST main settings from file: ${mainSettingsFileSource.settingsFile.show}"))
      loadedSettings <- EitherT(mainSettingsFileSource.load())
        .biSemiflatTap(
          error => logger.dError(s"Loading ReadonlyREST main settings from file failed: ${error.show}"),
          settings => logger.dDebug(s"Loaded ReadonlyREST main settings from file:\n${settings.rawSettings.rawYaml.show}")
        )
        .leftMap(error => StartingFailure(error.show))
    } yield loadedSettings
  }

  private def loadTestSettingsFromIndex() = {
    for {
      _ <- lift(logger.info(s"Loading ReadonlyREST test settings from index: ${testSettingsIndexSource.settingsIndex.show} ..."))
      loadedSettings <- EitherT(testSettingsIndexSource.load())
        .biSemiflatTap(
          error => logger.dInfo(s"Loading ReadonlyREST test settings from index failed: ${error.show}"),
          settings => logger.dDebug(s"Loaded ReadonlyREST test settings from index: ${settings.rawSettings.rawYaml.show}")
        )
        .leftMap(error => StartingFailure(error.show))
        .map(Option(_))
    } yield loadedSettings
  }

  private def lift[A](value: => A): EitherT[Task, Nothing, A] = EitherT(Task.delay(Right(value)))
}
