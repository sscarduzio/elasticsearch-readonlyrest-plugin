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
package org.elasticsearch.plugin.readonlyrest.integration;

import org.elasticsearch.plugin.readonlyrest.utils.containers.ESWithReadonlyRestContainer;
import org.elasticsearch.plugin.readonlyrest.utils.containers.ESWithReadonlyRestContainerUtils;
import org.elasticsearch.plugin.readonlyrest.utils.containers.LdapContainer;
import org.elasticsearch.plugin.readonlyrest.utils.containers.MultiContainer;
import org.elasticsearch.plugin.readonlyrest.utils.containers.MultiContainerDependent;
import org.elasticsearch.plugin.readonlyrest.utils.gradle.RorPluginGradleProject;
import org.elasticsearch.plugin.readonlyrest.utils.integration.ElasticsearchTweetsInitializer;
import org.elasticsearch.plugin.readonlyrest.utils.integration.ReadonlyRestedESAssertions;
import org.junit.ClassRule;
import org.junit.Test;

import static org.elasticsearch.plugin.readonlyrest.utils.integration.ReadonlyRestedESAssertions.assertions;

public class LdapIntegrationSecondOptionTests {

  @ClassRule
  public static MultiContainerDependent<ESWithReadonlyRestContainer> container =
    ESWithReadonlyRestContainerUtils.create(
      RorPluginGradleProject.fromSystemProperty(),
      new MultiContainer.Builder()
        .add("LDAP1", () -> LdapContainer.create("/ldap_integration_1st/ldap.ldif"))
        .add("LDAP2", () -> LdapContainer.create("/ldap_integration_1st/ldap.ldif"))
        .build(),
      "/ldap_integration_1st/elasticsearch.yml",
      new ElasticsearchTweetsInitializer()
    );

  @Test
  public void usersFromGroup1CanSeeTweets() throws Exception {
    ReadonlyRestedESAssertions assertions = assertions(container);
    assertions.assertUserHasAccessToIndex("cartman", "user2", "twitter");
    assertions.assertUserHasAccessToIndex("bong", "user1", "twitter");
  }

  @Test
  public void usersFromOutsideOfGroup1CannotSeeTweets() throws Exception {
    assertions(container).assertUserAccessToIndexForbidden("morgan", "user1", "twitter");
  }

  @Test
  public void unauthenticatedUserCannotSeeTweets() throws Exception {
    assertions(container).assertUserAccessToIndexForbidden("cartman", "wrong_password", "twitter");
  }

  @Test
  public void usersFromGroup3CanSeeFacebookPosts() throws Exception {
    ReadonlyRestedESAssertions assertions = assertions(container);
    assertions.assertUserHasAccessToIndex("cartman", "user2", "facebook");
    assertions.assertUserHasAccessToIndex("bong", "user1", "facebook");
    assertions.assertUserHasAccessToIndex("morgan", "user1", "facebook");
  }

}
