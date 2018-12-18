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
import tech.beshu.ror.utils.containers.ESWithReadonlyRestContainer;
import tech.beshu.ror.utils.containers.ESWithReadonlyRestContainerUtils;
import tech.beshu.ror.utils.containers.LdapContainer;
import tech.beshu.ror.utils.containers.MultiContainer;
import tech.beshu.ror.utils.containers.MultiContainerDependent;
import tech.beshu.ror.utils.gradle.RorPluginGradleProject;
import tech.beshu.ror.utils.integration.ElasticsearchTweetsInitializer;
import tech.beshu.ror.utils.integration.ReadonlyRestedESAssertions;

import static tech.beshu.ror.utils.integration.ReadonlyRestedESAssertions.assertions;

public class LdapAutenticationLocalAuthorizationTests {

  @ClassRule
  public static MultiContainerDependent<ESWithReadonlyRestContainer> container2 =
      ESWithReadonlyRestContainerUtils.create(
          RorPluginGradleProject.fromSystemProperty(),
          new MultiContainer.Builder()
              .add("LDAP1", () -> LdapContainer.create("/ldap_separate_authc_authz_mixed_local/ldap.ldif"))
              .build(),
          "/ldap_authc_local_authz/elasticsearch.yml",
          new ElasticsearchTweetsInitializer()
      );

  @Test
  public void checkCartmanCanSeeTwitter() throws Exception {
    ReadonlyRestedESAssertions assertions = assertions(container2);
    assertions.assertUserHasAccessToIndex("cartman", "user2", "twitter");
  }

  @Test
  public void checkUnicodedBibloCanSeeTwitter() throws Exception {
    ReadonlyRestedESAssertions assertions = assertions(container2);
    assertions.assertUserHasAccessToIndex("Bìlbö Bággįnš", "user2", "twitter");
  }

  @Test
  public void checkMorganCanSeeFacebook() throws Exception {
    ReadonlyRestedESAssertions assertions = assertions(container2);
    assertions.assertUserHasAccessToIndex("morgan", "user1", "facebook");
  }

  @Test
  public void checkMorganCannotSeeTwitter() throws Exception {
    ReadonlyRestedESAssertions assertions = assertions(container2);
    assertions.assertUserAccessToIndexForbidden("morgan", "user1", "twitter");
  }

  @Test
  public void checkCartmanCannotSeeFacebook() throws Exception {
    ReadonlyRestedESAssertions assertions = assertions(container2);
    assertions.assertUserAccessToIndexForbidden("cartman", "user2", "facebook");
  }

}
