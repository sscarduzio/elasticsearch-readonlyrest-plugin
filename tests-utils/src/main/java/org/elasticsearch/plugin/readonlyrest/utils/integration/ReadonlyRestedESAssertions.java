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
package org.elasticsearch.plugin.readonlyrest.utils.integration;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.plugin.readonlyrest.utils.containers.ESWithReadonlyRestContainer;
import org.elasticsearch.plugin.readonlyrest.utils.containers.MultiContainerDependent;
import org.elasticsearch.plugin.readonlyrest.utils.httpclient.RestClient;
import org.junit.Assert;

import java.io.IOException;
import java.net.URISyntaxException;

public class ReadonlyRestedESAssertions {

  private final MultiContainerDependent<ESWithReadonlyRestContainer> multiContainerDependent;

  public static ReadonlyRestedESAssertions assertions(MultiContainerDependent<ESWithReadonlyRestContainer> container) {
    return new ReadonlyRestedESAssertions(container);
  }

  public ReadonlyRestedESAssertions(MultiContainerDependent<ESWithReadonlyRestContainer> container) {
    this.multiContainerDependent = container;
  }

  public void assertUserHasAccessToIndex(String name, String password, String index)
      throws IOException, URISyntaxException {
    assertGetIndexResponseCode(
        multiContainerDependent.getContainer().getBasicAuthClient(name, password), index, 200
    );
  }

  public void assertReverseProxyUserHasAccessToIndex(String headerName, String userId, String index)
      throws IOException, URISyntaxException {
    assertGetIndexResponseCode(
        multiContainerDependent.getContainer().getClient(new BasicHeader(headerName, userId)), index, 200
    );
  }

  public void assertUserAccessToIndexForbidden(String name, String password, String index)
      throws IOException, URISyntaxException {
    assertGetIndexResponseCode(
        multiContainerDependent.getContainer().getBasicAuthClient(name, password), index, 401
    );
  }

  public void assertReverseProxyAccessToIndexForbidden(String headerName, String userId, String index)
      throws IOException, URISyntaxException {
    assertGetIndexResponseCode(
        multiContainerDependent.getContainer().getClient(new BasicHeader(headerName, userId)), index, 401
    );
  }

  public void assertGetIndexResponseCode(RestClient client, String index, int expectedCode)
      throws IOException, URISyntaxException {
    HttpResponse response = client.execute(new HttpGet(client.from(index)));
    Assert.assertEquals(expectedCode, response.getStatusLine().getStatusCode());
  }

}
