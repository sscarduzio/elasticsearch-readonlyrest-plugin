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
import cats.data.NonEmptyList
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.tools.core.patches.internal.modifiers.securityPolicyFiles.AddAdditionalPermissions

import java.nio.charset.{CharacterCodingException, StandardCharsets}

class SecurityPolicyFileModifierSuite extends AnyWordSpec with Matchers {

  private val modifier = new AddAdditionalPermissions(
    NonEmptyList.one(PermissionDefinition("java.lang.RuntimePermission", "createClassLoader"))
  )

  "SecurityPolicyFileModifier" should {
    "add the permission and keep the non-ASCII characters of a UTF-8 policy file" in {
      File.usingTemporaryDirectory() { directory =>
        val policyFile = (directory / "plugin-security.policy")
          .writeByteArray(
            policyLines(
              "grant {",
              "  // zażółć gęślą jaźń",
              "  permission java.io.FilePermission \"/tmp/ż\", \"read\";",
              "};"
            )
              .getBytes(StandardCharsets.UTF_8)
          )

        modifier(policyFile)

        new String(policyFile.byteArray, StandardCharsets.UTF_8) should be(
          policyLines(
            "grant {",
            "permission java.lang.RuntimePermission \"createClassLoader\";",
            "  // zażółć gęślą jaźń",
            "  permission java.io.FilePermission \"/tmp/ż\", \"read\";",
            "};"
          )
        )
      }
    }
    "fail and keep the policy file as it is when the file is not valid UTF-8" in {
      File.usingTemporaryDirectory() { directory =>
        val content =
          "grant {".getBytes(StandardCharsets.UTF_8) ++ Array(0xff.toByte) ++ "\n};\n".getBytes(StandardCharsets.UTF_8)
        val policyFile = (directory / "plugin-security.policy").writeByteArray(content)

        a[CharacterCodingException] should be thrownBy modifier(policyFile)

        policyFile.byteArray.toList should be(content.toList)
        directory.list.map(_.name).toList should be(List("plugin-security.policy"))
      }
    }
  }

  private def policyLines(lines: String*): String = lines.map(_ + System.lineSeparator).mkString
}
