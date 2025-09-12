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
import tech.beshu.ror.settings.source.FileSettingsSource
import tech.beshu.ror.utils.ScalaOps.*

class ForceLoadRorSettingsFromFileLoader(mainSettingsFileSource: FileSettingsSource[MainRorSettings])
  extends StartingRorSettingsLoader with Logging {

  override def load(): Task[Either[StartingFailure, (MainRorSettings, Option[TestRorSettings])]] = {
    val result = for {
      _ <- lift(logger.info(s"Loading ReadonlyREST main settings from file: ${mainSettingsFileSource.settingsFile.show}"))
      settings <- EitherT(mainSettingsFileSource.load())
        .biSemiflatTap(
          error => logger.dError(s"Loading ReadonlyREST main settings from file failed: ${error.show}"),
          settings => logger.dDebug(s"Loaded ReadonlyREST main settings from file: ${settings.rawSettings.rawYaml.show}")
        )
        .leftMap(error => StartingFailure(error.show))
    } yield (settings, None)
    result.value
  }

  private def lift[A](value: => A): EitherT[Task, Nothing, A] = EitherT(Task.delay(Right(value)))

}