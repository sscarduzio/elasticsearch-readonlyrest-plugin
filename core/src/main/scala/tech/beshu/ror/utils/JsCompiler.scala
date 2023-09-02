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
package tech.beshu.ror.utils

import org.mozilla.javascript.{Context, ScriptableObject}

import scala.util.Try

object JsCompiler {

  private val (mozillaJsContext: Context, scope: ScriptableObject) = synchronized {
    val context = Context.enter
    context.setApplicationClassLoader(this.getClass.getClassLoader)
    (context, context.initStandardObjects)
  }

  def compile(jsCodeString: String): Try[AnyRef] = synchronized {
    Try {
      val jsScript = mozillaJsContext.compileString(jsCodeString, "js", 1, null)
      val result = jsScript.exec(mozillaJsContext, scope)
      result
    }
  }
}
