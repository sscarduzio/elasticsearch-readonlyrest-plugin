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

import org.apache.http.Header;
import org.apache.http.message.BasicHeader;
import org.junit.ClassRule;
import org.junit.Test;
import tech.beshu.ror.utils.assertions.ReadonlyRestedESAssertions;
import tech.beshu.ror.utils.containers.*;
import tech.beshu.ror.utils.elasticsearch.ElasticsearchTweetsInitializer;
import tech.beshu.ror.utils.gradle.RorPluginGradleProjectJ;

import static tech.beshu.ror.utils.assertions.ReadonlyRestedESAssertions.assertions;

public class LdapIntegrationGroupsTests {

  @ClassRule
  public static MultiContainerDependent<ESWithReadonlyRestContainer> multiContainerDependent =
      ESWithReadonlyRestContainerUtils.create(
          RorPluginGradleProjectJ.fromSystemProperty(),
          new MultiContainer.Builder()
              .add("LDAP1", () -> JavaLdapContainer.create("/ldap_integration_group_headers/ldap.ldif"))
              .build(),
          "/ldap_integration_group_headers/elasticsearch.yml",
          new ElasticsearchTweetsInitializer()
      );


  @Test
  public void checkCartmanWithoutCurrGroupHeader() throws Exception {
    ReadonlyRestedESAssertions assertions = assertions(multiContainerDependent.getContainer());
    assertions.assertUserHasAccessToIndex("cartman", "user2", "twitter");
  }

  @Test
  public void checkCartmanWithGroup1AsCurrentGroup() throws Exception {
    ReadonlyRestedESAssertions assertions = assertions(multiContainerDependent.getContainer());
    assertions.assertUserHasAccessToIndex("cartman", "user2", "twitter", response -> null,
        httpRequest -> {
          httpRequest.addHeader("x-ror-current-group", "group1");
          return null;
        });
  }

  @Test
  public void checkCartmanWithGroup1AsCurrentGroupPassedAsValueOfAuthorizationHeader() throws Exception {
    Header authenticationHeader = new BasicHeader(
        "Authorization",
        "Basic Y2FydG1hbjp1c2VyMg==, ror_metadata=eyJoZWFkZXJzIjpbIngtcm9yLWN1cnJlbnQtZ3JvdXA6Z3JvdXAxIiwgImhlYWRlcjE6eHl6Il19"
    );
    ReadonlyRestedESAssertions assertions = assertions(multiContainerDependent.getContainer());
    assertions.assertUserHasAccessToIndex(authenticationHeader, "twitter", response -> null, httpRequest -> null);
  }

  @Test
  public void checkCartmanWithGroup3AsCurrentGroup() throws Exception {
    ReadonlyRestedESAssertions assertions = assertions(multiContainerDependent.getContainer());
    assertions.assertUserHasAccessToIndex("cartman", "user2", "twitter", response -> null,
        httpRequest -> {
          httpRequest.addHeader("x-ror-current-group", "group3");
          return null;
        });
  }

}
