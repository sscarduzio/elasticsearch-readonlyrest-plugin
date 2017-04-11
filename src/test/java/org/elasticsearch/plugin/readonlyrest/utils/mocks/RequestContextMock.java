package org.elasticsearch.plugin.readonlyrest.utils.mocks;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import org.elasticsearch.plugin.readonlyrest.acl.LoggedUser;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;

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
