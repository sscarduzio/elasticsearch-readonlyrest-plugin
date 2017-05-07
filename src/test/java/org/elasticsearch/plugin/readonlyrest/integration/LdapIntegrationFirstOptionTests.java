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

import org.elasticsearch.plugin.readonlyrest.testutils.containers.ESWithReadonlyRestContainer;
import org.elasticsearch.plugin.readonlyrest.testutils.containers.ESWithReadonlyRestContainerUtils;
import org.elasticsearch.plugin.readonlyrest.testutils.containers.LdapContainer;
import org.elasticsearch.plugin.readonlyrest.testutils.containers.MultiContainer;
import org.elasticsearch.plugin.readonlyrest.testutils.containers.MultiContainerDependent;
import org.elasticsearch.plugin.readonlyrest.testutils.integration.ElasticsearchTweetsInitializer;
import org.elasticsearch.plugin.readonlyrest.testutils.integration.ReadonlyRestedESAssertions;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;

public class LdapIntegrationFirstOptionTests {

  @ClassRule
  public static MultiContainerDependent<ESWithReadonlyRestContainer> container =
      ESWithReadonlyRestContainerUtils.create(
          new MultiContainer.Builder()
              .add("LDAP1", () -> LdapContainer.create("/test_example.ldif"))
              .add("LDAP2", () -> LdapContainer.create("/test_example.ldif"))
              .build(),
          "/ldap_first_option_test_elasticsearch.yml",
          new ElasticsearchTweetsInitializer()
      );

  private static ReadonlyRestedESAssertions assertions = new ReadonlyRestedESAssertions(container);

  @Test
  public void usersFromGroup1CanSeeTweets() throws IOException {
    assertions.assertUserHasAccessToIndex("cartman", "user2", "twitter");
    assertions.assertUserHasAccessToIndex("bong", "user1", "twitter");
  }

  @Test
  public void usersFromOutsideOfGroup1CannotSeeTweets() throws IOException {
    assertions.assertUserAccessToIndexForbidden("morgan", "user1", "twitter");
  }

  @Test
  public void unauthenticatedUserCannotSeeTweets() throws IOException {
    assertions.assertUserAccessToIndexForbidden("cartman", "wrong_password", "twitter");
  }

  @Test
  public void usersFromGroup3CanSeeFacebookPosts() throws IOException {
    assertions.assertUserHasAccessToIndex("cartman", "user2", "facebook");
    assertions.assertUserHasAccessToIndex("bong", "user1", "facebook");
    assertions.assertUserHasAccessToIndex("morgan", "user1", "facebook");
  }

}
