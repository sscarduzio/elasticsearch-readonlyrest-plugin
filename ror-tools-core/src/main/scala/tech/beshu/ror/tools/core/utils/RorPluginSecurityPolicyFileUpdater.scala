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
package tech.beshu.ror.tools.core.utils

import java.io.{File, PrintWriter}
import scala.io.Source
import scala.util.Using

object AddCreateClassLoaderPermission extends RorPluginSecurityPolicyFileUpdater {

  def apply(policyFile: File): Unit = {
    addPermission(policyFile, "permission java.lang.RuntimePermission \"createClassLoader\";")
  }
}

abstract class RorPluginSecurityPolicyFileUpdater {

  protected def addPermission(policyFile: File, permission: String): Unit = {
    val tmp = new File(policyFile.getPath + ".tmp") // Temporary File
    Using(new PrintWriter(tmp)) { writer =>
      Using(Source.fromFile(policyFile)) { source =>
        source.getLines()
          .zipWithIndex
          .flatMap {
            case (line, 1) => List(permission, line)
            case (line, _) => List(line)
          }
          .foreach(line =>
            writer.println(line)
          )
      }
    }
    tmp.renameTo(policyFile)
  }
}
