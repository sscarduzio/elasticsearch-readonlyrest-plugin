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

import just.semver.SemVer
import tech.beshu.ror.tools.core.patches.base.TransportNetty4AwareEsPatch
import tech.beshu.ror.tools.core.patches.internal.RorPluginDirectory
import tech.beshu.ror.tools.core.patches.internal.filePatchers._
import tech.beshu.ror.tools.core.patches.internal.modifiers.bytecodeJars._
import tech.beshu.ror.tools.core.patches.internal.modifiers.securityPolicyFiles.AddCreateClassLoaderPermission

import scala.language.postfixOps

private[patches] class Es89xPatch(rorPluginDirectory: RorPluginDirectory, esVersion: SemVer)
  extends TransportNetty4AwareEsPatch(rorPluginDirectory, esVersion,
    new ElasticsearchJarPatchCreator(
      OpenModule,
      ModifyPolicyUtilClass,
      new RepositoriesServiceAvailableForClusterServiceForAnyTypeOfNode(esVersion)
    ),
    new RorSecurityPolicyPatchCreator(
      AddCreateClassLoaderPermission
    ),
    new XPackCoreJarPatchCreator(
      OpenModule
    ),
    new XPackSecurityJarPatchCreator(
      OpenModule,
      DeactivateSecurityActionFilter,
      DeactivateAuthenticationServiceInHttpTransport
    )

  )
