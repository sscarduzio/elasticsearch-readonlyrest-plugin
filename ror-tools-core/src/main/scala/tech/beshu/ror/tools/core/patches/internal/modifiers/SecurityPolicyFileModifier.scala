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
package tech.beshu.ror.tools.core.patches.internal.modifiers

import better.files.File
import tech.beshu.ror.tools.core.utils.FileUtils.*

import java.io.{File => JFile, PrintWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, StandardCopyOption}
import scala.io.{Codec, Source}
import scala.util.Using

private[patches] abstract class SecurityPolicyFileModifier extends FileModifier {

  protected def addPermission(policyFile: File, permission: String): Unit = {
    val policyJFile = policyFile.toJava
    policyFile.replaceKeepingPermissionsAndOwner {
      // The file is read completely before the temporary file is created. A read error then stops
      // the change, and the original file stays as it is.
      val lines = Using.resource(Source.fromFile(policyJFile)(Codec.UTF8))(_.getLines().toList)
      val tmp = new JFile(policyJFile.getPath + ".tmp") // Temporary File
      Using.resource(new PrintWriter(tmp, StandardCharsets.UTF_8)) { writer =>
        lines.zipWithIndex
          .flatMap {
            case (line, 1) => List(permission, line)
            case (line, _) => List(line)
          }
          .foreach(line => writer.println(line))
      }
      // NOT File.renameTo: on Windows it fails when the destination exists, and it reports that by
      // returning false, so the permission would be dropped without a trace. Files.move throws instead.
      Files.move(tmp.toPath, policyJFile.toPath, StandardCopyOption.REPLACE_EXISTING)
    }
  }

}
