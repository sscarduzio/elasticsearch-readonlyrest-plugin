package org.elasticsearch.plugin.readonlyrest.utils.integration;

import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.plugin.readonlyrest.utils.containers.ESWithReadonlyRestContainer;
import org.junit.Assert;

import java.io.IOException;

public class ReadonlyRestedESAssertions {

  private final ESWithReadonlyRestContainer container;

  public ReadonlyRestedESAssertions(ESWithReadonlyRestContainer container) {
    this.container = container;
  }

  public void assertUserHasAccessToIndex(String name, String password, String index) throws IOException {
    assertGetIndexResponseCode(container.getBasicAuthClient(name, password), index, 200);
  }

  public void assertReverseProxyUserHasAccessToIndex(String headerName, String userId, String index) throws IOException {
    assertGetIndexResponseCode(container.getClient(new BasicHeader(headerName, userId)), index, 200);
  }

  public void assertUserAccessToIndexForbidden(String name, String password, String index) throws IOException {
    assertGetIndexResponseCode(container.getBasicAuthClient(name, password), index, 401);
  }

  public void assertReverseProxyAccessToIndexForbidden(String headerName, String userId, String index) throws IOException {
    assertGetIndexResponseCode(container.getClient(new BasicHeader(headerName, userId)), index, 401);
  }

  public void assertGetIndexResponseCode(RestClient client, String index, int expectedCode) throws IOException {
    try {

      Response response = client.performRequest("GET", "/" + index);
      Assert.assertEquals(expectedCode, response.getStatusLine().getStatusCode());
    } catch (ResponseException ex) {
      Assert.assertEquals(expectedCode, ex.getResponse().getStatusLine().getStatusCode());
    }
  }

}
