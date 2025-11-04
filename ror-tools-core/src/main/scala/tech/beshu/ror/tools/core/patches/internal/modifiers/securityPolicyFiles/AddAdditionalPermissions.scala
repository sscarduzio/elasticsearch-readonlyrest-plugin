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
import tech.beshu.ror.tools.core.patches.internal.modifiers.SecurityPolicyFileModifier

import java.security.Permission
import java.security.SecurityPermission

private[patches] class AddAdditionalPermissions(permission: NonEmptyList[Permission]) extends SecurityPolicyFileModifier {

  override def apply(policyFile: File): Unit = {
    permission.toList.foreach { permission =>
      addAdditionalPermission(policyFile, permission)
    }
  }

  private def addAdditionalPermission(policyFile: File, permission: Permission): Unit = {
    addPermission(policyFile, s"permission ${permission.getClass.getName} \"${permission.getName}\";")
  }
}
private[patches] object AddAdditionalPermissions {
  val createClassLoaderRuntimePermission = new RuntimePermission("createClassLoader")
  val getPropertySecurityPermission = new SecurityPermission("getProperty.*")
}