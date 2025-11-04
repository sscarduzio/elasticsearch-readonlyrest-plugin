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
import tech.beshu.ror.tools.core.patches.internal.filePatchers.*
import tech.beshu.ror.tools.core.patches.internal.modifiers.bytecodeJars.*
import tech.beshu.ror.tools.core.patches.internal.modifiers.bytecodeJars.authentication.DummyAuthenticationInAuthenticationChain
import tech.beshu.ror.tools.core.patches.internal.modifiers.bytecodeJars.authorization.DummyAuthorizeInAuthorizationService
import tech.beshu.ror.tools.core.patches.internal.modifiers.bytecodeJars.entitlements.{ModifyEntitlementInitializationClass, ModifyEntitlementRuntimePolicyParserClass, ModifyFilesEntitlementsValidationClass}
import tech.beshu.ror.tools.core.utils.EsUtil.es8182

import scala.language.postfixOps

private[patches] class Es818xPatch(rorPluginDirectory: RorPluginDirectory, esVersion: SemVer)
  extends TransportNetty4AwareEsPatch(rorPluginDirectory, esVersion,
    new ElasticsearchJarPatchCreator(
      OpenModule,
      new RepositoriesServiceAvailableForClusterServiceForAnyTypeOfNode(esVersion)
    ),
    new EntitlementJarPatchCreator(
      esVersion match {
        case v if v >= es8182 => new ModifyFilesEntitlementsValidationClass(esVersion)
        case _ => new ModifyEntitlementInitializationClass(esVersion)
      },
      ModifyEntitlementRuntimePolicyParserClass,
    ),
    new XPackCoreJarPatchCreator(
      OpenModule,
      DisabledAsyncSearchSecurity
    ),
    new XPackSecurityJarPatchCreator(
      OpenModule,
      DeactivateGetRequestCacheKeyDifferentiatorInSecurity,
      new DummyAuthenticationInAuthenticationChain(esVersion),
      new DummyAuthorizeInAuthorizationService(esVersion),
    ),
    new XPackIlmJarPatchCreator(
      OpenModule
    )
  )
