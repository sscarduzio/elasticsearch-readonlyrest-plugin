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
package tech.beshu.ror.tools.core.patches

import cats.data.NonEmptyList
import just.semver.SemVer
import tech.beshu.ror.tools.core.patches.base.TransportNetty4AwareEsPatch
import tech.beshu.ror.tools.core.patches.internal.RorPluginDirectory
import tech.beshu.ror.tools.core.patches.internal.filePatchers.*
import tech.beshu.ror.tools.core.patches.internal.modifiers.bytecodeJars.actions.ModifyRestHasPrivilegesActionClass
import tech.beshu.ror.tools.core.patches.internal.modifiers.bytecodeJars.authentication.ModifyAuthenticationChainClass
import tech.beshu.ror.tools.core.patches.internal.modifiers.bytecodeJars.authorization.*
import tech.beshu.ror.tools.core.patches.internal.modifiers.bytecodeJars.permissions.ModifyPolicyUtilClass
import tech.beshu.ror.tools.core.patches.internal.modifiers.bytecodeJars.security.{ModifyCreateComponentsInSecurityClass, ModifySecurityClass}
import tech.beshu.ror.tools.core.patches.internal.modifiers.bytecodeJars.services.{CreateApiKeyServiceBridgeClass, CreateServiceAccountServiceBridgeClass, ModifyRepositoriesServiceClass}
import tech.beshu.ror.tools.core.patches.internal.modifiers.securityPolicyFiles.AddAdditionalPermissions
import tech.beshu.ror.tools.core.patches.internal.modifiers.securityPolicyFiles.AddAdditionalPermissions.*

import scala.language.postfixOps

private[patches] class Es81xPatch(rorPluginDirectory: RorPluginDirectory, esVersion: SemVer)
  extends TransportNetty4AwareEsPatch(rorPluginDirectory, esVersion,
    ElasticsearchJarPatchCreator(
      ModifyPolicyUtilClass(esVersion, NonEmptyList.of(
        createClassLoaderRuntimePermission, getPropertySecurityPermission
      )),
      CreateApiKeyServiceBridgeClass,
      CreateServiceAccountServiceBridgeClass,
      ModifyRepositoriesServiceClass(esVersion)
    ),
    RorSecurityPolicyPatchCreator(
      AddAdditionalPermissions(NonEmptyList.of(
        createClassLoaderRuntimePermission, getPropertySecurityPermission
      )),
    ),
    XPackCoreJarPatchCreator(
      ModifySimpleRoleClass,
      ModifyApplicationPermissionClass,
    ),
    XPackSecurityJarPatchCreator(
      ModifyCreateComponentsInSecurityClass,
      CreateRorAuthorizationInfoProviderClass(esVersion),
      ModifyAuthenticationChainClass(esVersion),
      ModifyAuthorizationServiceClass(esVersion),
      ModifyRBACEngineClass,
      ModifyRestHasPrivilegesActionClass,
      ModifySecurityClass,
    ),
  )
