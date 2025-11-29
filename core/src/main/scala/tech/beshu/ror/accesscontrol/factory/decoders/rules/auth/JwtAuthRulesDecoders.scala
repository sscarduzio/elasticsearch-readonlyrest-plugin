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
import tech.beshu.ror.accesscontrol.blocks.definitions.JwtDef
import tech.beshu.ror.accesscontrol.blocks.rules.auth.{JwtAuthRule, JwtAuthenticationRule, JwtAuthorizationRule, JwtPseudoAuthorizationRule}
import tech.beshu.ror.accesscontrol.domain.GroupsLogic
import tech.beshu.ror.accesscontrol.factory.GlobalSettings

object JwtAuthRulesDecoders
  extends JwtLikeRulesDecoders[
    JwtAuthenticationRule,
    JwtAuthorizationRule,
    JwtPseudoAuthorizationRule,
    JwtAuthRule,
    JwtDef,
  ] with Logging {

  override def humanReadableName: String = "JWT"

  override def createAuthenticationRule(definition: JwtDef, globalSettings: GlobalSettings): JwtAuthenticationRule =
    new JwtAuthenticationRule(JwtAuthenticationRule.Settings(definition), globalSettings.userIdCaseSensitivity)

  override def createAuthorizationRule(definition: JwtDef, groupsLogic: GroupsLogic): JwtAuthorizationRule =
    new JwtAuthorizationRule(JwtAuthorizationRule.Settings(definition, groupsLogic))

  override def createAuthorizationRuleWithoutGroups(definition: JwtDef): JwtPseudoAuthorizationRule =
    new JwtPseudoAuthorizationRule(JwtPseudoAuthorizationRule.Settings(definition))

  override def createAuthRule(authnRule: JwtAuthenticationRule, authzRule: JwtAuthorizationRule): JwtAuthRule =
    new JwtAuthRule(authnRule, authzRule)

  override def createAuthRuleWithoutGroups(authnRule: JwtAuthenticationRule, authzRule: JwtPseudoAuthorizationRule): JwtAuthRule =
    new JwtAuthRule(authnRule, authzRule)

  override def serializeDefinitionId(definition: JwtDef): String =
    definition.id.value.value

}
