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
package tech.beshu.ror.unit.utils

import cats.effect.Resource
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Suite
import tech.beshu.ror.boot.{ReadonlyRest, RorInstance}
import tech.beshu.ror.settings.es.EsConfigBasedRorSettings

trait WithReadonlyrestBootSupport {
  this: Suite =>

  protected def withReadonlyRest(readonlyRestAndSettings: (ReadonlyRest, EsConfigBasedRorSettings))
                                (testCode: RorInstance => Any): Unit = {
    val (readonlyRest, esConfigBasedRorSettings) = readonlyRestAndSettings
    withReadonlyRestExt((readonlyRest, esConfigBasedRorSettings, ())) { case (rorInstance, ()) => testCode(rorInstance) }
  }

  protected def withReadonlyRestExt[EXT](readonlyRestAndSettingsAndExt: (ReadonlyRest, EsConfigBasedRorSettings, EXT))
                                        (testCode: (RorInstance, EXT) => Any): Unit = {
    val (readonlyRest, esConfigBasedRorSettings, ext) = readonlyRestAndSettingsAndExt
    Resource
      .make(
        acquire = readonlyRest
          .start(esConfigBasedRorSettings)
          .flatMap {
            case Right(startedInstance) => Task.now(startedInstance)
            case Left(startingFailure) => Task.raiseError(new Exception(s"$startingFailure"))
          }
      )(
        release = _.stop()
      )
      .use { startedInstance =>
        Task.delay {
          testCode(startedInstance, ext)
        }
      }
      .runSyncUnsafe()
  }

}
