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
import tech.beshu.ror.tools.core.patches.base.SimpleEsPatch
import tech.beshu.ror.tools.core.patches.internal.RorPluginDirectory
import tech.beshu.ror.tools.core.patches.internal.filePatchers.*
import tech.beshu.ror.tools.core.patches.internal.modifiers.bytecodeJars.*
import tech.beshu.ror.tools.core.patches.internal.modifiers.bytecodeJars.authentication.DummyAuthenticationInAuthenticationServiceAuthenticator
import tech.beshu.ror.tools.core.patches.internal.modifiers.bytecodeJars.authorization.DummyAuthorizeInAuthorizationService
import tech.beshu.ror.tools.core.patches.internal.modifiers.bytecodeJars.permissions.{AlwaysGrantApplicationPermission, ModifyBootstrapPolicyUtilClass}
import tech.beshu.ror.tools.core.patches.internal.modifiers.securityPolicyFiles.AddAdditionalPermissions
import tech.beshu.ror.tools.core.patches.internal.modifiers.securityPolicyFiles.AddAdditionalPermissions.getPropertySecurityPermission

import scala.language.postfixOps

private[patches] class Es711xPatch(rorPluginDirectory: RorPluginDirectory, esVersion: SemVer)
  extends SimpleEsPatch(rorPluginDirectory, esVersion,
    new ElasticsearchJarPatchCreator(
      new ModifyBootstrapPolicyUtilClass(esVersion, NonEmptyList.of(
        getPropertySecurityPermission
      )),
      new RepositoriesServiceAvailableForClusterServiceForAnyTypeOfNode(esVersion),
      new SnapshotsServiceAvailableForClusterServiceForAnyTypeOfNode(esVersion)
    ),
    new RorSecurityPolicyPatchCreator(
      AddAdditionalPermissions(NonEmptyList.of(
        getPropertySecurityPermission
      )),
    ),
    new XPackCoreJarPatchCreator(
      AlwaysGrantApplicationPermission,
    ),
    new XPackSecurityJarPatchCreator(
      DeactivateGetRequestCacheKeyDifferentiatorInSecurity,
      DeactivateSecurityServerTransportInterceptor,
      new DummyAuthenticationInAuthenticationServiceAuthenticator(esVersion),
      new DummyAuthorizeInAuthorizationService(esVersion),
    )
  )
