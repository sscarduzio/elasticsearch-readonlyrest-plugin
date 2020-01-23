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

package tech.beshu.ror.integration;

import org.junit.ClassRule;
import org.junit.Test;
import tech.beshu.ror.utils.assertions.ReadonlyRestedESAssertions;
import tech.beshu.ror.utils.containers.*;
import tech.beshu.ror.utils.elasticsearch.ElasticsearchTweetsInitializer;
import tech.beshu.ror.utils.gradle.RorPluginGradleProjectJ;

public class LdapIntegrationSecondOptionTests {

  @ClassRule
  public static MultiContainerDependent<ESWithReadonlyRestContainer> multiContainerDependent =
      ESWithReadonlyRestContainerUtils.create(
          RorPluginGradleProjectJ.fromSystemProperty(),
          new MultiContainer.Builder()
              .add("LDAP1", () -> JavaLdapContainer.create("/ldap_integration_1st/ldap.ldif"))
              .add("LDAP2", () -> JavaLdapContainer.create("/ldap_integration_1st/ldap.ldif"))
              .build(),
          "/ldap_integration_2nd/ldap_second_option_test_elasticsearch.yml",
          new ElasticsearchTweetsInitializer()
      );

  @Test
  public void usersFromGroup1CanSeeTweets() throws Exception {
    ReadonlyRestedESAssertions assertions = ReadonlyRestedESAssertions.assertions(multiContainerDependent.getContainer());
    assertions.assertUserHasAccessToIndex("cartman", "user2", "twitter");
    assertions.assertUserHasAccessToIndex("bong", "user1", "twitter");
  }

  @Test
  public void usersFromOutsideOfGroup1CannotSeeTweets() throws Exception {
    ReadonlyRestedESAssertions
        .assertions(multiContainerDependent.getContainer())
        .assertIndexNotFound("morgan", "user1", "twitter");
  }

  @Test
  public void unauthenticatedUserCannotSeeTweets() throws Exception {
    ReadonlyRestedESAssertions
        .assertions(multiContainerDependent.getContainer())
        .assertUserAccessToIndexForbidden("cartman", "wrong_password", "twitter");
  }

  @Test
  public void usersFromGroup3CanSeeFacebookPosts() throws Exception {
    ReadonlyRestedESAssertions assertions = ReadonlyRestedESAssertions.assertions(multiContainerDependent.getContainer());
    assertions.assertUserHasAccessToIndex("cartman", "user2", "facebook");
    assertions.assertUserHasAccessToIndex("bong", "user1", "facebook");
    assertions.assertUserHasAccessToIndex("morgan", "user1", "facebook");
  }

}