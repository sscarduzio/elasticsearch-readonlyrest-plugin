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

import tech.beshu.ror.accesscontrol.blocks.definitions.*
import tech.beshu.ror.accesscontrol.blocks.rules.auth.{JwtAuthRule, JwtAuthenticationRule, JwtAuthorizationRule}
import tech.beshu.ror.accesscontrol.domain.GroupsLogic
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.utils.RequestIdAwareLogging

object JwtAuthRulesDecoders
  extends JwtLikeRulesDecoders[
    JwtDef,
    JwtDefForAuthentication,
    JwtDefForAuthorization,
    JwtDefForAuth,
    JwtAuthenticationRule,
    JwtAuthorizationRule,
    JwtAuthRule,
  ] with RequestIdAwareLogging {

  override protected def ruleTypePrefix: String = "jwt"

  override protected def docsUrl: String = "https://docs.readonlyrest.com/elasticsearch#json-web-token-jwt-auth"

  override protected def createAuthenticationRule(definition: JwtDefForAuthentication,
                                                  globalSettings: GlobalSettings): JwtAuthenticationRule =
    new JwtAuthenticationRule(JwtAuthenticationRule.Settings(definition), globalSettings.userIdCaseSensitivity)

  override protected def createAuthorizationRule(definition: JwtDefForAuthorization,
                                                 groupsLogic: GroupsLogic): JwtAuthorizationRule =
    new JwtAuthorizationRule(JwtAuthorizationRule.Settings(definition, groupsLogic))

  override def createAuthRule(authnRule: JwtAuthenticationRule, authzRule: JwtAuthorizationRule): JwtAuthRule =
    new JwtAuthRule(authnRule, authzRule)

  override protected def serializeDefinitionId(definition: JwtDef): String =
    definition.id.value.value

}
