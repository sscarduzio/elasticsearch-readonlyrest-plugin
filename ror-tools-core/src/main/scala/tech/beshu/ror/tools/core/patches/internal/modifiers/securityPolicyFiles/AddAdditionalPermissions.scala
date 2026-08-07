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
package tech.beshu.ror.tools.core.patches.internal.modifiers.securityPolicyFiles

import better.files.File
import cats.data.NonEmptyList
import tech.beshu.ror.tools.core.patches.internal.modifiers.{PermissionDefinition, SecurityPolicyFileModifier}

private[patches] class AddAdditionalPermissions(permission: NonEmptyList[PermissionDefinition])
    extends SecurityPolicyFileModifier {

  override def apply(policyFile: File): Unit = {
    permission.toList.foreach { permission =>
      addAdditionalPermission(policyFile, permission)
    }
  }

  private def addAdditionalPermission(policyFile: File, permission: PermissionDefinition): Unit = {
    addPermission(policyFile, s"permission ${permission.className} \"${permission.name}\";")
  }

}

private[patches] object AddAdditionalPermissions {
  val createClassLoaderRuntimePermission =
    PermissionDefinition("java.lang.RuntimePermission", "createClassLoader")
  val getPropertySecurityPermission =
    PermissionDefinition("java.security.SecurityPermission", "getProperty.*")
  // bc-fips 2.1.3+ zeroizes DRBG buffers via a java.lang.ref.Cleaner, whose thread trips SecureSM on
  // ES older than 7.17.8. ES rejects this permission in a shipped plugin-security.policy (PolicyUtil
  // allowlist, since 7.11), so it can only be granted here, after the plugin is installed.
  val modifyArbitraryThreadPermission =
    PermissionDefinition("org.elasticsearch.secure_sm.ThreadPermission", "modifyArbitraryThread")
}
