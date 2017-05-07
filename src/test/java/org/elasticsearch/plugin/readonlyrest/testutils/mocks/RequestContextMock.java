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
package org.elasticsearch.plugin.readonlyrest.testutils.mocks;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import org.elasticsearch.plugin.readonlyrest.acl.domain.LoggedUser;
import org.elasticsearch.plugin.readonlyrest.requestcontext.RequestContext;

import java.util.Base64;
import java.util.Optional;

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
}
