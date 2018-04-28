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
import tech.beshu.ror.requestcontext.RequestContext;

import java.util.Base64;
import java.util.HashMap;
import java.util.Optional;

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
}
