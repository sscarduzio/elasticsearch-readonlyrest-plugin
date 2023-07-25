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
package tech.beshu.ror.accesscontrol.blocks.variables.transformation

import java.util.regex.PatternSyntaxException

private[transformation] object Utils {

  implicit class PatternSyntaxExceptionOps(val exception: PatternSyntaxException) extends AnyVal {
    def getPrettyMessage: String = { // pattern syntax exception message creates multiline string
      val sb = new StringBuilder
      sb.append(exception.getDescription)
      if (exception.getIndex >= 0) {
        sb.append(" near index ")
        sb.append(exception.getIndex)
      }
      sb.toString
    }
  }

}
