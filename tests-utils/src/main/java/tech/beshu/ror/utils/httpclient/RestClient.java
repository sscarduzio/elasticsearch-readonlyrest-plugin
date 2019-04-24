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
package tech.beshu.ror.utils.httpclient;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.http.Header;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.config.SocketConfig;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.client.StandardHttpRequestRetryHandler;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.ssl.SSLContextBuilder;
import tech.beshu.ror.utils.misc.Tuple;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class RestClient {

  private final CloseableHttpClient underlying;
  private final String host;
  private final int port;
  private final boolean ssl;

  public RestClient(boolean ssl, String host, int port) {
    this.ssl = ssl;
    this.host = host;
    this.port = port;
    this.underlying = createUnderlyingClient(Optional.empty());
  }

  public RestClient(boolean ssl, String host, int port, Optional<Tuple<String, String>> basicAuth, Header... headers) {
    this.ssl = ssl;
    this.host = host;
    this.port = port;
    this.underlying = createUnderlyingClient(basicAuth, headers);
  }

  private CloseableHttpClient createUnderlyingClient(Optional<Tuple<String, String>> basicAuth, Header... headers) {
    HttpClientBuilder builder = null;

    if (ssl) {
      try {
        SSLContextBuilder sslCtxBuilder = new SSLContextBuilder();
        sslCtxBuilder.loadTrustMaterial(null, new TrustSelfSignedStrategy());
        SSLConnectionSocketFactory sslsf = new SSLConnectionSocketFactory(sslCtxBuilder.build(), SSLConnectionSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);
        builder = HttpClients.custom().setSSLSocketFactory(sslsf);
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
    else {
      builder = HttpClientBuilder.create();
    }

    // Common ops
    if (basicAuth.isPresent()) {
      System.out.println("SETTING CREDENTIALS " + (basicAuth.isPresent() ? (basicAuth.get().v1() + ":" + basicAuth.get().v2()) : ""));
      Header auth = BasicScheme.authenticate(
        new UsernamePasswordCredentials(basicAuth.get().v1(), basicAuth.get().v2()), "UTF-8", false);

      Header[] tmp = Arrays.copyOf(headers, headers.length + 1);
      tmp[tmp.length - 1] = auth;
      headers = tmp;
    }

    int timeout = 5;
    builder
      .setRetryHandler(new StandardHttpRequestRetryHandler(3, true))
      .setDefaultHeaders(Lists.newArrayList(headers))
      .setDefaultSocketConfig(SocketConfig.custom().build())
      .setDefaultRequestConfig(
          RequestConfig.custom()
              .setConnectTimeout(timeout * 1000)
              .setConnectionRequestTimeout(timeout * 1000)
              .setSocketTimeout(timeout * 1000).build()
      );

    return builder.build();
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
    URIBuilder uriBuilder = new URIBuilder();
    uriBuilder
        .setScheme(ssl ? "https" : "http")
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

  public CloseableHttpResponse execute(HttpUriRequest req) throws IOException {
    return underlying.execute(req);
  }

}
