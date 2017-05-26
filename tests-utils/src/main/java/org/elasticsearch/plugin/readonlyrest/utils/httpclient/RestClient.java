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
package org.elasticsearch.plugin.readonlyrest.utils.httpclient;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.config.SocketConfig;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.StandardHttpRequestRetryHandler;
import org.apache.http.message.BasicNameValuePair;
import org.elasticsearch.plugin.readonlyrest.utils.Tuple;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class RestClient {

  private final HttpClient underlying;
  private final String host;
  private final int port;

  public RestClient(String host, int port) {
    this.underlying = createUnderlyingClient(Optional.empty());
    this.host = host;
    this.port = port;
  }

  public RestClient(String host, int port, Optional<Tuple<String, String>> basicAuth, Header... headers) {
    this.underlying = createUnderlyingClient(basicAuth, headers);
    this.host = host;
    this.port = port;
  }

  private HttpClient createUnderlyingClient(Optional<Tuple<String, String>> basicAuth, Header... headers) {
    HttpClientBuilder builder = HttpClientBuilder.create();
    if (basicAuth.isPresent()) {
      CredentialsProvider provider = new BasicCredentialsProvider();
      UsernamePasswordCredentials credentials = new UsernamePasswordCredentials(basicAuth.get().v1(), basicAuth.get().v2());
      provider.setCredentials(AuthScope.ANY, credentials);
      builder.setDefaultCredentialsProvider(provider);
    }
    return builder
        .setRetryHandler(new StandardHttpRequestRetryHandler(3, true))
        .setDefaultHeaders(Lists.newArrayList(headers))
        .setDefaultSocketConfig(SocketConfig.custom().build())
        .build();
  }

  public HttpClient getUnderlyingClient() {
    return underlying;
  }

  public String getHost() {
    return host;
  }

  public int getPort() {
    return port;
  }

  public URI from(String path) throws URISyntaxException {
    return from(path, Maps.newHashMap());
  }

  public URI from(String path, Map<String, String> queryParams) throws URISyntaxException {
    URIBuilder uriBuilder = new URIBuilder()
        .setScheme("http")
        .setHost(host)
        .setPort(port)
        .setPath(("/" + path + "/").replaceAll("//", "/"));
    if (!queryParams.isEmpty()) {
      uriBuilder.setParameters(
          queryParams.entrySet().stream()
              .map(e -> new BasicNameValuePair(e.getKey(), e.getValue()))
              .collect(Collectors.toList())
      );
    }
    return uriBuilder.build();
  }

  public HttpResponse execute(HttpUriRequest req) throws IOException {
    return underlying.execute(req);
  }

}
