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

import com.google.common.base.Joiner;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.junit.ClassRule;
import org.junit.Test;
import tech.beshu.ror.utils.containers.ESWithReadonlyRestContainer;
import tech.beshu.ror.utils.containers.ESWithReadonlyRestContainerUtils;
import tech.beshu.ror.utils.containers.LdapContainer;
import tech.beshu.ror.utils.containers.MultiContainer;
import tech.beshu.ror.utils.containers.MultiContainerDependent;
import tech.beshu.ror.utils.gradle.RorPluginGradleProject;
import tech.beshu.ror.utils.elasticsearch.ElasticsearchTweetsInitializer;
import tech.beshu.ror.utils.assertions.ReadonlyRestedESAssertions;

import static org.junit.Assert.assertEquals;
import static tech.beshu.ror.utils.assertions.ReadonlyRestedESAssertions.assertions;

public class LdapIntegrationWithLocalGroupsTests {

  @ClassRule
  public static MultiContainerDependent<ESWithReadonlyRestContainer> multiContainerDependent =
      ESWithReadonlyRestContainerUtils.create(
          RorPluginGradleProject.fromSystemProperty(),
          new MultiContainer.Builder()
              .add("LDAP1", () -> LdapContainer.create("/ldap_separate_authc_authz_mixed_local/ldap.ldif"))
              .build(),
          "/ldap_separate_authc_authz_mixed_local/elasticsearch.yml",
          new ElasticsearchTweetsInitializer()
      );

  @Test
  public void checkCartmanRespHeaders() throws Exception {
    ReadonlyRestedESAssertions assertions = assertions(multiContainerDependent.getContainer());
    assertions.assertUserHasAccessToIndex("cartman", "user2", "twitter", response -> {
          System.out.println("resp headers" + Joiner.on(",").join(response.getAllHeaders()));
          assertEquals("group1,group3", getHeader("x-ror-available-groups", response));
          assertEquals("cartman", getHeader("x-ror-username", response));
          assertEquals("group1", getHeader("x-ror-current-group", response));
          assertEquals(".kibana_group1", getHeader("x-ror-kibana_index", response));
          return null;
        },
        httpRequest -> {
          httpRequest.addHeader("x-ror-current-group", "group1");
          return null;
        });
  }


  @Test
  public void checkCartmanRespHeadersWithCurrentGroupReqHeader() throws Exception {
    ReadonlyRestedESAssertions assertions = assertions(multiContainerDependent.getContainer());
    assertions.assertUserHasAccessToIndex("cartman", "user2", "twitter", response -> {
          System.out.println("resp headers" + Joiner.on(",\n").join(response.getAllHeaders()));
          assertEquals("group1,group3", getHeader("x-ror-available-groups", response));
          assertEquals("cartman", getHeader("x-ror-username", response));
          assertEquals("group3", getHeader("x-ror-current-group", response));
          assertEquals(".kibana_group3", getHeader("x-ror-kibana_index", response));
          return null;
        },
        httpRequest -> {
          httpRequest.addHeader("x-ror-current-group", "group3");
          return null;
        });
  }

  private String getHeader(String headerName, HttpResponse resp) {
    Header h = resp.getFirstHeader(headerName);
    if (h == null) {
      return "";
    }
    return h.getValue();
  }
}
