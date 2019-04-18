package tech.beshu.ror.integration;

import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.junit.ClassRule;
import org.junit.Test;
import tech.beshu.ror.utils.containers.ESWithReadonlyRestContainer;
import tech.beshu.ror.utils.gradle.RorPluginGradleProject;
import tech.beshu.ror.utils.httpclient.RestClient;
import tech.beshu.ror.utils.integration.ElasticsearchTweetsInitializer;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;

import static org.junit.Assert.assertEquals;

public class MSearchWithFilterTests {

  @ClassRule
  public static ESWithReadonlyRestContainer container = ESWithReadonlyRestContainer.create(
      RorPluginGradleProject.fromSystemProperty(), "/msearch_with_filter/elasticsearch.yml",
      Optional.of(new ElasticsearchTweetsInitializer())
  );

  @Test
  public void userShouldOnlySeeFacebookPostsFilterTest() throws Exception {
    waitUntilAllIndexed();

    RestClient user1HttpClient = container.getBasicAuthClient("test1", "dev");
    RestClient user2HttpClient = container.getBasicAuthClient("test2", "dev");

    SearchResult searchResult1 = mSearchCall(user1HttpClient);
    SearchResult searchResult2 = mSearchCall(user1HttpClient);
    SearchResult searchResult3 = mSearchCall(user2HttpClient);
    SearchResult searchResult4 = mSearchCall(user2HttpClient);
    SearchResult searchResult5 = mSearchCall(user1HttpClient);

    assertSearchResult("facebook", searchResult1);
    assertSearchResult("facebook", searchResult2);
    assertSearchResult("twitter", searchResult3);
    assertSearchResult("twitter", searchResult4);
    assertSearchResult("facebook", searchResult5);
  }

  private void assertSearchResult(String expectedIndex, SearchResult searchResult) {
    assertEquals(200, (int) searchResult.responseCode);
    assertEquals(2, searchResult.results.size());
    searchResult.results.forEach(result -> assertEquals(expectedIndex, result.get("_index")));
  }

  private void waitUntilAllIndexed() {
    RetryPolicy<SearchResult> retryPolicy = new RetryPolicy<SearchResult>()
        .handleIf(resultsContainsLessElementsThan(4))
        .withMaxRetries(20)
        .withDelay(Duration.ofMillis(500))
        .withMaxDuration(Duration.ofSeconds(10));
    Failsafe.with(retryPolicy).get(() -> mSearchCall(container.getAdminClient()));
  }

  private BiPredicate<SearchResult, Throwable> resultsContainsLessElementsThan(int count) {
    return (searchResult, throwable) -> throwable != null || searchResult == null || searchResult.results.size() < count;
  }

  private SearchResult mSearchCall(RestClient client) throws Exception {
    HttpPost request = new HttpPost(client.from("/_msearch"));
    request.addHeader("Content-type", "application/json");
    request.setEntity(new StringEntity(
        "{\"index\":\"*\"}\n" + "{\"query\" : {\"match_all\" : {}}}\n"
    ));

    try (CloseableHttpResponse response = client.execute(request)) {
      int statusCode = response.getStatusLine().getStatusCode();
      return statusCode != 200
          ? new SearchResult(statusCode, Lists.newArrayList())
          : new SearchResult(statusCode, getEntries(deserializeJsonBody(EntityUtils.toString(response.getEntity()))));
    }
  }

  private static class SearchResult {

    private final Integer responseCode;
    private final List<Map<String, Object>> results;

    SearchResult(Integer responseCode, List<Map<String, Object>> results) {
      this.responseCode = responseCode;
      this.results = results;
    }
  }

  public static Map<String, Object> deserializeJsonBody(String response) {
    Gson gson = new Gson();
    Type mapType = new TypeToken<HashMap<String, Object>>(){}.getType();
    return gson.fromJson(response, mapType);
  }

  public static List<Map<String, Object>> getEntries(Map<String, Object> result) {
    List<Map<String, Object>> responses = (List<Map<String, Object>>)result.get("responses");
    return (List<Map<String, Object>>) ((Map<String, Object>)responses.get(0).get("hits")).get("hits");
  }
}
