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
package tech.beshu.ror.utils.js

import cats.effect.Resource
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.mozilla.javascript.Context

import scala.util.Try

object MozillaJsCompiler
  extends JsCompiler {

  override def compile(jsCodeString: String): Try[Unit] = {
    createJsContext
      .use { context =>
        Task.delay(runCompilation(context, jsCodeString))
      }
      // at the moment there is no need to do it better, because the JS compiler is used only during documentation parsing
      .runSyncUnsafe()
  }

  private def createJsContext: Resource[Task, Context] = {
    Resource
      .make(
        acquire = Task.delay {
          val context = Context.enter
          context.setApplicationClassLoader(this.getClass.getClassLoader)
          context
        }
      )(
        release = context =>
          Task.delay(context.close())
      )
  }

  private def runCompilation(context: Context, jsCodeString: String) = Try {
    val jsScript = context.compileString(jsCodeString, "js", 1, null)
    jsScript.exec(context, context.initStandardObjects)
    ()
  }
}
