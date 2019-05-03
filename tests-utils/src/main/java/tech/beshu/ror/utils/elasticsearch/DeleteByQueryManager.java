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
package tech.beshu.ror.utils.elasticsearch;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import tech.beshu.ror.utils.httpclient.RestClient;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

public class DeleteByQueryManager {

  private final RestClient restClient;

  public DeleteByQueryManager(RestClient restClient) {
    this.restClient = restClient;
  }

  public DeleteByQueryResult delete(String indexName, String query) {
    try (CloseableHttpResponse response = restClient.execute(createDeleteByQueryRequest(indexName, query))) {
      return new DeleteByQueryResult(response.getStatusLine().getStatusCode());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private HttpPost createDeleteByQueryRequest(String indexName, String query) {
    try {
      HttpPost request = new HttpPost(restClient.from("/" + indexName + "/_delete_by_query"));
      request.addHeader("Content-type", "application/json");
      request.setEntity(new StringEntity(query));
      return request;
    } catch (UnsupportedEncodingException e) {
      throw new RuntimeException(e);
    }
  }

  public static class DeleteByQueryResult {

    private final Integer responseCode;

    DeleteByQueryResult(Integer responseCode) {
      this.responseCode = responseCode;
    }

    public int getResponseCode() {
      return responseCode;
    }
  }
}
