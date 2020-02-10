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

package tech.beshu.ror.utils.assertions;

import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.message.BasicHeader;
import org.junit.Assert;
import tech.beshu.ror.utils.containers.ESWithReadonlyRestContainer;
import tech.beshu.ror.utils.httpclient.RestClient;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.function.Function;

public class ReadonlyRestedESAssertions {

  private final ESWithReadonlyRestContainer rorContainer;

  public ReadonlyRestedESAssertions(ESWithReadonlyRestContainer container) {
    this.rorContainer = container;
  }

  public static ReadonlyRestedESAssertions assertions(ESWithReadonlyRestContainer container) {
    return new ReadonlyRestedESAssertions(container);
  }

  public void assertUserHasAccessToIndex(String name, String password, String index) throws IOException, URISyntaxException {
    assertGetIndexResponseCode(rorContainer.getBasicAuthClient(name, password), index, 200, null, null);
  }

  public void assertUserHasAccessToIndex(String name, String password, String index, Function<HttpResponse, Void> responseHandler,
      Function<HttpRequest, Void> requestHandler)

      throws IOException, URISyntaxException {
    assertGetIndexResponseCode(
        rorContainer.getBasicAuthClient(name, password), index, 200, responseHandler, requestHandler
    );
  }

  public void assertReverseProxyUserHasAccessToIndex(String headerName, String userId, String index)
      throws IOException, URISyntaxException {
    assertGetIndexResponseCode(
        rorContainer.getClient(new BasicHeader(headerName, userId)), index, 200
    );
  }

  public void assertUserAccessToIndexForbidden(String name, String password, String index)
      throws IOException, URISyntaxException {
    assertGetIndexResponseCode(
        rorContainer.getBasicAuthClient(name, password), index, 403
    );
  }

  public void assertIndexNotFound(String name, String password, String index)
      throws IOException, URISyntaxException {
    assertGetIndexResponseCode(
        rorContainer.getBasicAuthClient(name, password), index, 404
    );
  }

  public void assertReverseProxyAccessToIndexForbidden(String headerName, String userId, String index)
      throws IOException, URISyntaxException {
    assertGetIndexResponseCode(
        rorContainer.getClient(new BasicHeader(headerName, userId)), index, 403
    );
  }

  public void assertGetIndexResponseCode(RestClient client, String index, int expectedCode) throws IOException, URISyntaxException {
    assertGetIndexResponseCode(client, index, expectedCode, null, null);
  }

  public void assertGetIndexResponseCode(RestClient client, String index, int expectedCode, Function<HttpResponse, Void> responseHandler,
      Function<HttpRequest, Void> requestHandler)
      throws IOException, URISyntaxException {
    HttpGet req = new HttpGet(client.from(index));
    if (requestHandler != null) {
      requestHandler.apply(req);
    }

    HttpResponse response = client.execute(req);
    Assert.assertEquals(expectedCode, response.getStatusLine().getStatusCode());

    if (responseHandler != null) {
      responseHandler.apply(response);
    }

  }

}
