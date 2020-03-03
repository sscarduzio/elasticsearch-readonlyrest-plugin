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

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import org.apache.http.Header;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.junit.ClassRule;
import org.junit.Test;
import tech.beshu.ror.utils.containers.*;
import tech.beshu.ror.utils.elasticsearch.ElasticsearchTweetsInitializer;
import tech.beshu.ror.utils.gradle.RorPluginGradleProjectJ;
import tech.beshu.ror.utils.httpclient.RestClient;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;

public class DuplicatedResponseHeadersIssueTests {

  @ClassRule
  public static MultiContainerDependent<ESWithReadonlyRestContainer> multiContainerDependent =
      ESWithReadonlyRestContainerUtils.create(
          RorPluginGradleProjectJ.fromSystemProperty(),
          new MultiContainer.Builder()
              .add("EXT1", () -> WireMockContainer.create(
                  "/duplicated_response_headers_issue/auth.json",
                  "/duplicated_response_headers_issue/brian_groups.json",
                  "/duplicated_response_headers_issue/freddie_groups.json"
              ))
              .build(), "/duplicated_response_headers_issue/elasticsearch.yml",
          new ElasticsearchTweetsInitializer()
      );

  @Test
  public void everySearchCallForEachUserShouldReturnTheSameResult() throws Exception {
    RestClient freddieHttpClient = multiContainerDependent.getContainer().getBasicAuthClient("freddie", "freddie");
    RestClient brianHttpClient = multiContainerDependent.getContainer().getBasicAuthClient("brian", "brian");

    SearchResult b1 = searchCall(brianHttpClient);
    SearchResult f1 = searchCall(freddieHttpClient);
    SearchResult f2 = searchCall(freddieHttpClient);
    SearchResult b2 = searchCall(brianHttpClient);
    SearchResult b3 = searchCall(brianHttpClient);
    SearchResult f3 = searchCall(freddieHttpClient);
    SearchResult b4 = searchCall(brianHttpClient);
    SearchResult b5 = searchCall(brianHttpClient);
    SearchResult b6 = searchCall(brianHttpClient);
    SearchResult b7 = searchCall(brianHttpClient);
    SearchResult b8 = searchCall(brianHttpClient);

    assertEquals(f2, f1);
    assertEquals(f3, f1);
    assertEquals(b2, b1);
    assertEquals(b3, b1);
    assertEquals(b4, b1);
    assertEquals(b5, b1);
    assertEquals(b6, b1);
    assertEquals(b7, b1);
    assertEquals(b8, b1);
  }

  private SearchResult searchCall(RestClient client) throws Exception {
    HttpGet request = new HttpGet(client.from("/neg*/_search"));
    try (CloseableHttpResponse response = client.execute(request)) {
      return new SearchResult(
          response.getStatusLine().getStatusCode(),
          Lists.newArrayList(response.getAllHeaders())
          );
    }
  }

  private static class SearchResult {

    private final Integer responseCode;
    private final List<SimpleHeader> headers;

    SearchResult(Integer responseCode, List<Header> headers) {
      this.responseCode = responseCode;
      this.headers = headers.stream().map(h -> new SimpleHeader(h.getName(), h.getValue())).collect(Collectors.toList());
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == null) return false;
      if (getClass() != obj.getClass()) return false;
      final SearchResult other = (SearchResult) obj;
      return Objects.equals(this.responseCode, other.responseCode)
          && Iterables.elementsEqual(this.headers, other.headers);
    }

    @Override
    public int hashCode() {
      return Objects.hash(this.responseCode, this.headers);
    }
  }

  private static class SimpleHeader {
    private final String name;
    private final String value;

    SimpleHeader(String name, String value) {
      this.name = name;
      this.value = value;
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == null) return false;
      if (getClass() != obj.getClass()) return false;
      final SimpleHeader other = (SimpleHeader) obj;
      return Objects.equals(this.name, other.name)
          && Objects.equals(this.value, other.value);
    }

    @Override
    public int hashCode() {
      return Objects.hash(this.name, this.value);
    }

  }
}
