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

import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.definitions.RorKbnDef
import tech.beshu.ror.accesscontrol.blocks.rules.auth.{RorKbnAuthRule, RorKbnAuthenticationRule, RorKbnAuthorizationRule}
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupIdPattern
import tech.beshu.ror.accesscontrol.domain.{GroupIds, GroupsLogic}
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.utils.RefinedUtils.nes
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

object RorKbnRulesDecoders
  extends JwtLikeRulesDecoders[
    RorKbnAuthenticationRule,
    RorKbnAuthorizationRule,
    RorKbnAuthorizationRule,
    RorKbnAuthRule,
    RorKbnDef,
  ] with Logging {

  override def humanReadableName: String = "ROR Kibana"

  override def createAuthenticationRule(definition: RorKbnDef, globalSettings: GlobalSettings): RorKbnAuthenticationRule =
    new RorKbnAuthenticationRule(RorKbnAuthenticationRule.Settings(definition), globalSettings.userIdCaseSensitivity)

  override def createAuthorizationRule(definition: RorKbnDef, groupsLogic: GroupsLogic): RorKbnAuthorizationRule =
    new RorKbnAuthorizationRule(RorKbnAuthorizationRule.Settings(definition, groupsLogic))

  override def createAuthorizationRuleWithoutGroups(definition: RorKbnDef): RorKbnAuthorizationRule =
    createAuthorizationRule(definition, GroupsLogic.AnyOf(GroupIds(UniqueNonEmptyList.of(GroupIdPattern.fromNes(nes("*"))))))

  override def createAuthRule(authnRule: RorKbnAuthenticationRule, authzRule: RorKbnAuthorizationRule): RorKbnAuthRule =
    new RorKbnAuthRule(authnRule, authzRule)

  override def createAuthRuleWithoutGroups(authnRule: RorKbnAuthenticationRule, authzRule: RorKbnAuthorizationRule): RorKbnAuthRule =
    new RorKbnAuthRule(authnRule, authzRule)

  override def serializeDefinitionId(definition: RorKbnDef): String =
    definition.id.value.value

}
