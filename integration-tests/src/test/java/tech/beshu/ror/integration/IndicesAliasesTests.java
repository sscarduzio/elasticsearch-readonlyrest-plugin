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
import tech.beshu.ror.utils.elasticsearch.DocumentManager;
import tech.beshu.ror.utils.elasticsearch.SearchManager;
import tech.beshu.ror.utils.elasticsearch.SearchManager.SearchResult;
import tech.beshu.ror.utils.gradle.RorPluginGradleProject;

import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static tech.beshu.ror.utils.assertions.EnhancedAssertion.assertNAttempts;

public class IndicesAliasesTests {

  @ClassRule
  public static ESWithReadonlyRestContainer container =
      ESWithReadonlyRestContainer.create(
          RorPluginGradleProject.fromSystemProperty(), "/indices_aliases_test/elasticsearch.yml",
          Optional.of(client -> {
            DocumentManager documentManager = new DocumentManager(client);
            documentManager.insertDoc("/my_data/test/1", "{\"hello\":\"world\"}");
            documentManager.insertDoc("/my_data/test/2", "{\"hello\":\"there\", \"public\":1}");
            documentManager.insertDoc("/my_data/_alias/public_data", "{\"filter\":{\"term\":{\"public\":1}}}");
          })
      );

  private SearchManager restrictedDevSearchManager = new SearchManager(container.getBasicAuthClient("restricted", "dev"));
  private SearchManager unrestrictedDevSearchManager = new SearchManager(container.getBasicAuthClient("unrestricted", "dev"));

  @Test
  public void testDirectIndexQuery() {
    assertNAttempts(3, () -> {
      SearchResult result = unrestrictedDevSearchManager.search("/my_data/_search");
      assertEquals(200, result.getResponseCode());
      assertEquals(2, result.getResults().size());
      return null;
    });
  }

  @Test
  public void testAliasQuery() {
    assertNAttempts(3, () -> {
      SearchResult result = unrestrictedDevSearchManager.search("/public_data/_search");
      assertEquals(200, result.getResponseCode());
      assertEquals(1, result.getResults().size());
      return null;
    });
  }

  @Test
  public void testAliasAsWildcard() {
    assertNAttempts(3, () -> {
      SearchResult result = unrestrictedDevSearchManager.search("/pub*/_search");
      assertEquals(200, result.getResponseCode());
      assertEquals(1, result.getResults().size());
      return null;
    });
  }

  // Tests with indices rule restricting to "pub*"

  @Test
  public void testRestrictedPureIndex() {
    assertNAttempts(3, () -> {
      SearchResult result = restrictedDevSearchManager.search("/my_data/_search");
      assertEquals(401, result.getResponseCode());
      return null;
    });
  }

  @Test
  public void testRestrictedAlias() {
    assertNAttempts(3, () -> {
      SearchResult result = restrictedDevSearchManager.search("/public_data/_search");
      assertEquals(200, result.getResponseCode());
      assertEquals(1, result.getResults().size());
      return null;
    });
  }

  @Test
  public void testRestrictedAliasAsWildcard() {
    assertNAttempts(3, () -> {
      SearchResult result = restrictedDevSearchManager.search("/public*/_search");
      assertEquals(200, result.getResponseCode());
      assertEquals(1, result.getResults().size());
      return null;
    });
  }

  @Test
  public void testRestrictedAliasAsHalfWildcard() {
    assertNAttempts(3, () -> {
      SearchResult result = restrictedDevSearchManager.search("/pu*/_search");
      assertEquals(200, result.getResponseCode());
      assertEquals(1, result.getResults().size());
      return null;
    });
  }

}
