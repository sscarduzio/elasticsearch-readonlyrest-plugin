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
package tech.beshu.ror.accesscontrol.factory.decoders.rules.auth

import tech.beshu.ror.accesscontrol.blocks.definitions.RorKbnDef
import tech.beshu.ror.accesscontrol.blocks.rules.auth.{RorKbnAuthRule, RorKbnAuthenticationRule, RorKbnAuthorizationRule}
import tech.beshu.ror.accesscontrol.domain.GroupsLogic
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.utils.RequestIdAwareLogging

object RorKbnRulesDecoders
  extends JwtLikeRulesDecoders[
    RorKbnDef,
    RorKbnDef,
    RorKbnDef,
    RorKbnDef,
    RorKbnAuthenticationRule,
    RorKbnAuthorizationRule,
    RorKbnAuthRule,
  ] with RequestIdAwareLogging {

  override protected def ruleTypePrefix: String = "ror_kbn"

  override protected def docsUrl: String = "https://docs.readonlyrest.com/elasticsearch?q=ror#ror_kbn_auth"

  override protected def createAuthenticationRule(definition: RorKbnDef, globalSettings: GlobalSettings): RorKbnAuthenticationRule =
    new RorKbnAuthenticationRule(RorKbnAuthenticationRule.Settings(definition), globalSettings.userIdCaseSensitivity)

  override protected def createAuthorizationRule(definition: RorKbnDef, groupsLogic: GroupsLogic): RorKbnAuthorizationRule =
    new RorKbnAuthorizationRule(RorKbnAuthorizationRule.Settings(definition, groupsLogic))

  override protected def createAuthRule(authnRule: RorKbnAuthenticationRule, authzRule: RorKbnAuthorizationRule): RorKbnAuthRule =
    new RorKbnAuthRule(authnRule, authzRule)

  override protected def serializeDefinitionId(definition: RorKbnDef): String =
    definition.id.value.value

}
