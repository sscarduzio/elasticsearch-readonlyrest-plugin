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

package tech.beshu.ror.mocks;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import tech.beshu.ror.commons.domain.LoggedUser;
import tech.beshu.ror.commons.Constants;
import tech.beshu.ror.acl.domain.LoggedUser;
import tech.beshu.ror.httpclient.HttpMethod;
import tech.beshu.ror.requestcontext.RequestContext;

import java.util.Base64;
import java.util.HashMap;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class RequestContextMock {

  public static RequestContext mockedRequestContext(String user, String pass) {
    RequestContext mock = mock(RequestContext.class);
    when(mock.getHeaders()).thenReturn(
        Maps.newHashMap(ImmutableMap.<String, String>builder()
            .put("Authorization", "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes()))
            .build()));
    when(mock.getLoggedInUser()).thenReturn(Optional.of(new LoggedUser(user)));
    return mock;
  }

  public static RequestContext  mockedRequestContext(String user, String pass, String preferredGroup) {
    RequestContext mock = mock(RequestContext.class);
    when(mock.getHeaders()).thenReturn(
        Maps.newHashMap(ImmutableMap.<String, String>builder()
            .put("Authorization", "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes()))
            .put(Constants.HEADER_GROUP_CURRENT,preferredGroup)
            .build()));
    when(mock.getLoggedInUser()).thenReturn(Optional.of(new LoggedUser(user)));
    HashMap<String, String> respHeaders = Maps.newHashMap();

    when(mock.getResponseHeaders()).thenReturn(respHeaders);

    doAnswer(inv -> {
      respHeaders.put((String) inv.getArguments()[0], (String) inv.getArguments()[1]);
      return null;
    }).when(mock).setResponseHeader(any(String.class), any(String.class));

    return mock;
  }


  public static RequestContext mkSearchRequest(Set<String> initialIndices, Set<String> indicesInCluster, Set<String> expandedIndices) {
    return new RequestContext("search_test", new MockedESContext()) {
      @Override
      public Set<String> getExpandedIndices(Set<String> i) {
        return expandedIndices;
      }

      @Override
      public Set<String> getAllIndicesAndAliases() {
        return indicesInCluster;
      }

      @Override
      protected Boolean extractDoesInvolveIndices() {
        return true;
      }

      @Override
      protected Set<String> extractIndices() {
        return initialIndices;
      }

      @Override
      protected Boolean extractIsReadRequest() {
        return true;
      }

      @Override
      protected Boolean extractIsCompositeRequest() {
        return false;
      }

      @Override
      protected void writeIndices(Set<String> indices) {

      }

      @Override
      protected void commitResponseHeaders(Map<String, String> hmap) {

      }

      @Override
      public String getAction() {
        return "indices:data/read/search";
      }

      @Override
      public String getId() {
        return "test_request_" + UUID.randomUUID().toString();
      }

      @Override
      public String getContent() {
        return null;
      }

      @Override
      public Integer getContentLength() {
        return null;
      }

      @Override
      public HttpMethod getMethod() {
        return HttpMethod.GET;
      }

      @Override
      public String getUri() {
        return null;
      }

      @Override
      public String getType() {
        return "test-SearchRequest";
      }

      @Override
      public Long getTaskId() {
        return 1l;
      }

      @Override
      public String getRemoteAddress() {
        return null;
      }

      @Override
      protected Map<String, String> extractRequestHeaders() {
        return Maps.newHashMap();
      }

      @Override
      public String getHistoryString() {
        return "no history, this is a test search request";
      }

      @Override
      public String getMethodString() {
        return "GET";
      }

      @Override
      public Optional<String> getLoggedInUserName() {
        return Optional.empty();
      }
    };
  }
}
