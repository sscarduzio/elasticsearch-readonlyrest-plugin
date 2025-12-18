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

import cats.Show
import cats.data.EitherT
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.implicits.*
import tech.beshu.ror.settings.ror.source.ReadOnlySettingsSource
import tech.beshu.ror.settings.ror.{MainRorSettings, TestRorSettings}
import tech.beshu.ror.utils.ScalaOps.{EitherTOps, LoggerOps}

trait StartingRorSettingsLoader {
  this: Logging =>

  def load(): Task[Either[LoadingError, (MainRorSettings, Option[TestRorSettings])]]

  protected def loadSettingsFromSource[S: Show, E: Show](source: ReadOnlySettingsSource[S, E],
                                                         settingsDescription: String): EitherT[Task, LoadingError, S] = {
    for {
      _ <- EitherT.liftTask(logger.info(s"Loading ReadonlyREST $settingsDescription ..."))
      loadedSettings <- EitherT(source.load())
        .biSemiflatTap(
          error => logger.dInfo(s"Loading ReadonlyREST $settingsDescription failed: ${error.show}"),
          settings => logger.dDebug(s"Loaded ReadonlyREST $settingsDescription:\n${settings.show}")
        )
        .leftMap(error => error.show)
    } yield loadedSettings
  }

  type LoadingError = String
}
