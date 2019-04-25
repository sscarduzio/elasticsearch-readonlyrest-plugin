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

import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.junit.ClassRule;
import org.junit.Test;
import tech.beshu.ror.utils.containers.ESWithReadonlyRestContainer;
import tech.beshu.ror.utils.elasticsearch.SearchManager;
import tech.beshu.ror.utils.elasticsearch.SearchManager.SearchResult;
import tech.beshu.ror.utils.gradle.RorPluginGradleProject;
import tech.beshu.ror.utils.elasticsearch.ElasticsearchTweetsInitializer;

import java.time.Duration;
import java.util.Optional;
import java.util.function.BiPredicate;

import static org.junit.Assert.assertEquals;

public class MSearchWithFilterTests {

  @ClassRule
  public static ESWithReadonlyRestContainer container = ESWithReadonlyRestContainer.create(
      RorPluginGradleProject.fromSystemProperty(), "/msearch_with_filter/elasticsearch.yml",
      Optional.of(new ElasticsearchTweetsInitializer())
  );

  private String matchAllIndicesQuery =  "{\"index\":\"*\"}\n" + "{\"query\" : {\"match_all\" : {}}}\n";

  private SearchManager adminSearchManager = new SearchManager(container.getAdminClient());
  private SearchManager user1SearchManager = new SearchManager(container.getBasicAuthClient("test1", "dev"));
  private SearchManager user2SearchManager = new SearchManager(container.getBasicAuthClient("test2", "dev"));

  @Test
  public void userShouldOnlySeeFacebookPostsFilterTest() {
    waitUntilAllIndexed();

    SearchResult searchResult1 = user1SearchManager.mSearch(matchAllIndicesQuery);
    SearchResult searchResult2 = user1SearchManager.mSearch(matchAllIndicesQuery);
    SearchResult searchResult3 = user2SearchManager.mSearch(matchAllIndicesQuery);
    SearchResult searchResult4 = user2SearchManager.mSearch(matchAllIndicesQuery);
    SearchResult searchResult5 = user1SearchManager.mSearch(matchAllIndicesQuery);

    assertSearchResult("facebook", searchResult1);
    assertSearchResult("facebook", searchResult2);
    assertSearchResult("twitter", searchResult3);
    assertSearchResult("twitter", searchResult4);
    assertSearchResult("facebook", searchResult5);
  }

  private void assertSearchResult(String expectedIndex, SearchResult searchResult) {
    assertEquals(200, searchResult.getResponseCode());
    assertEquals(2, searchResult.getResults().size());
    searchResult.getResults().forEach(result -> assertEquals(expectedIndex, result.get("_index")));
  }

  private void waitUntilAllIndexed() {
    RetryPolicy<SearchResult> retryPolicy = new RetryPolicy<SearchResult>()
        .handleIf(resultsContainsLessElementsThan(4))
        .withMaxRetries(20)
        .withDelay(Duration.ofMillis(500))
        .withMaxDuration(Duration.ofSeconds(10));
    Failsafe.with(retryPolicy).get(() -> adminSearchManager.mSearch(matchAllIndicesQuery));
  }

  private BiPredicate<SearchResult, Throwable> resultsContainsLessElementsThan(int count) {
    return (searchResult, throwable) -> throwable != null || searchResult == null || searchResult.getResults().size() < count;
  }

}
