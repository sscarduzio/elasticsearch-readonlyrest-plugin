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
import tech.beshu.ror.utils.elasticsearch.ElasticsearchTweetsInitializer;
import tech.beshu.ror.utils.elasticsearch.SearchManagerJ;
import tech.beshu.ror.utils.elasticsearch.SearchManagerJ.MSearchResult;
import tech.beshu.ror.utils.gradle.RorPluginGradleProjectJ;

import java.time.Duration;
import java.util.Optional;
import java.util.function.BiPredicate;

import static org.junit.Assert.assertEquals;

public class MSearchWithFilterTests {

  @ClassRule
  public static ESWithReadonlyRestContainer container = ESWithReadonlyRestContainer.create(
      RorPluginGradleProjectJ.fromSystemProperty(), "/msearch_with_filter/elasticsearch.yml",
      Optional.of(new ElasticsearchTweetsInitializer())
  );

  private String matchAllIndicesQuery =  "{\"index\":\"*\"}\n" + "{\"query\" : {\"match_all\" : {}}}\n";

  private SearchManagerJ adminSearchManager = new SearchManagerJ(container.getAdminClient());
  private SearchManagerJ user1SearchManager = new SearchManagerJ(container.getBasicAuthClient("test1", "dev"));
  private SearchManagerJ user2SearchManager = new SearchManagerJ(container.getBasicAuthClient("test2", "dev"));

  @Test
  public void userShouldOnlySeeFacebookPostsFilterTest() {
    waitUntilAllIndexed();

    MSearchResult searchResult1 = user1SearchManager.mSearch(matchAllIndicesQuery);
    MSearchResult searchResult2 = user1SearchManager.mSearch(matchAllIndicesQuery);
    MSearchResult searchResult3 = user2SearchManager.mSearch(matchAllIndicesQuery);
    MSearchResult searchResult4 = user2SearchManager.mSearch(matchAllIndicesQuery);
    MSearchResult searchResult5 = user1SearchManager.mSearch(matchAllIndicesQuery);

    assertSearchResult("facebook", searchResult1);
    assertSearchResult("facebook", searchResult2);
    assertSearchResult("twitter", searchResult3);
    assertSearchResult("twitter", searchResult4);
    assertSearchResult("facebook", searchResult5);
  }

  private void assertSearchResult(String expectedIndex, MSearchResult searchResult) {
    assertEquals(200, searchResult.getResponseCode());
    assertEquals(2, searchResult.getMSearchHits().size());
    searchResult.getMSearchHits().forEach(result -> assertEquals(expectedIndex, result.get("_index")));
  }

  private void waitUntilAllIndexed() {
    RetryPolicy<MSearchResult> retryPolicy = new RetryPolicy<MSearchResult>()
        .handleIf(resultsContainsLessElementsThan(4))
        .withMaxRetries(20)
        .withDelay(Duration.ofMillis(500))
        .withMaxDuration(Duration.ofSeconds(10));
    Failsafe.with(retryPolicy).get(() -> adminSearchManager.mSearch(matchAllIndicesQuery));
  }

  private BiPredicate<MSearchResult, Throwable> resultsContainsLessElementsThan(int count) {
    return (searchResult, throwable) -> throwable != null || searchResult == null || searchResult.getMSearchHits().size() < count;
  }

}
