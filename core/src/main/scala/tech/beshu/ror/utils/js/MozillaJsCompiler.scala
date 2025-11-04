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

import scala.concurrent.duration.*
import scala.language.postfixOps
import scala.util.Try

object MozillaJsCompiler
  extends JsCompiler {

  override def compile(jsCodeString: String)
                      (implicit timeout: FiniteDuration = 10 seconds): Try[Unit] = {
    createJsContext
      .use { context =>
        Task.delay(runCompilation(context, jsCodeString))
      }
      // at the moment there is no need to do it better, because the JS compiler is used only during documentation parsing
      .runSyncUnsafe(timeout)
  }

  private def createJsContext: Resource[Task, Context] = {
    Resource
      .make(
        acquire = Task.delay {
          val context = Context.enter
          context.setApplicationClassLoader(this.getClass.getClassLoader)
          // The default optimization level is '0'. On that level, Rhino uses ClassLoader's and requires `createClassLoader` permission.
          //
          // Only on ES versions <8.0-8.18) AND on Windows Rhino throws `access denied` exception on that permission.
          // It is unclear, why it fails:
          //   - summary of changes in ES versions concerning security and JDKs:
          //     - ES versions before ES 8.0:
          //       - allow to add `createClassLoader` permission in `plugin-security.policy`
          //       - those versions work fine
          //     - ES <8.0,8.18):
          //       - do not allow to add the `createClassLoader` permission in `plugin-security.policy` - we add it through ES patching
          //       - ES creates empty SecurityManager on ES startup
          //       - the bundled JDK is Eclipse Temurin
          //     - ES <8.18-9.x>:
          //       - have new model of permission, we still add permission through patching
          //       - ES no longer creates empty SecurityManager on startup
          //       - the bundled JDK changed to Oracle distribution
          //       - those versions again work fine
          //   - it is confirmed (by decompilation and debugging), that the ROR patch with the permissions is installed correctly, detects ROR plugin and applies permissions
          //   - Rhino checks available permissions when it sets its default optimization level, so it thinks that it has the permission
          //   - but the operation fails on missing permission
          //   - there are a few possibilities what is causing the issue:
          //     - maybe in the affected versions Eclipse Temurin JDK somehow works in a different way on Windows and Linux (in version 8.18 JDK changed to Oracle)
          //     - maybe the empty SecurityManager created by ES <8.0,8.18) somehow confuses Rhino permission checks and interferes with permissions we add through patching
          //     - or some combination of all those factors
          //
          // In order to omit using ClassLoader's we need to set optimization level to `-1` (a bit worse performance, but it concerns only reading few values from config)
          context.setOptimizationLevel(-1)
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
