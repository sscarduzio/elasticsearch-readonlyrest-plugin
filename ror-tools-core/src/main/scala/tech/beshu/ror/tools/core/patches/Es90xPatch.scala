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
import tech.beshu.ror.tools.core.patches.internal.modifiers.bytecodeJars.actions.ModifyRestHasPrivilegesActionClass
import tech.beshu.ror.tools.core.patches.internal.modifiers.bytecodeJars.authentication.ModifyAuthenticationChainClass
import tech.beshu.ror.tools.core.patches.internal.modifiers.bytecodeJars.authorization.{CreateRorAuthorizationInfoProviderClass, CreateRorIndicesResolverClass, ModifyAuthorizationServiceClass, ModifyRBACEngineClass}
import tech.beshu.ror.tools.core.patches.internal.modifiers.bytecodeJars.entitlements.*
import tech.beshu.ror.tools.core.patches.internal.modifiers.bytecodeJars.security.{ModifyAsyncSearchSecurityClass, ModifySecurityClass, ModifySecurityContextClass}
import tech.beshu.ror.tools.core.patches.internal.modifiers.bytecodeJars.services.ModifyRepositoriesServiceClass
import tech.beshu.ror.tools.core.utils.EsUtil.{es902, es903}

import scala.language.postfixOps

private[patches] class Es90xPatch(rorPluginDirectory: RorPluginDirectory, esVersion: SemVer)
  extends TransportNetty4AwareEsPatch(rorPluginDirectory, esVersion,
    ElasticsearchJarPatchCreator(
      OpenModule,
      ModifyRepositoriesServiceClass(esVersion)
    ),
    EntitlementJarPatchCreator(
      esVersion match {
        case v if v >= es902 => ModifyFilesEntitlementsValidationClass(esVersion)
        case _ => ModifyEntitlementInitializationClass(esVersion)
      },
      esVersion match {
        case v if v >= es903 => ModifyPolicyCheckerImplClass(esVersion)
        case _ => ModifyPolicyManagerClass(esVersion)
      },
      ModifyPolicyParserClass,
    ),
    XPackCoreJarPatchCreator(
      OpenModule,
      ModifyAsyncSearchSecurityClass,
      ModifySecurityContextClass,
    ),
    XPackSecurityJarPatchCreator(
      OpenModule,
      CreateRorAuthorizationInfoProviderClass(esVersion),
      CreateRorIndicesResolverClass(),
      ModifyAuthenticationChainClass(esVersion),
      ModifyAuthorizationServiceClass(esVersion),
      ModifyRBACEngineClass,
      ModifyRestHasPrivilegesActionClass,
      ModifySecurityClass,
    ),
    XPackIlmJarPatchCreator(
      OpenModule
    )
  )
