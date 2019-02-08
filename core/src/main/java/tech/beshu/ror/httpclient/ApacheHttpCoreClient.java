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

package tech.beshu.ror.httpclient;

import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.ssl.SSLContexts;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.commons.shims.es.LoggerShim;
import tech.beshu.ror.settings.HttpConnectionSettings;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.AccessController;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivilegedAction;
import java.security.cert.X509Certificate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;


/**
 * Created by sscarduzio on 03/07/2017.
 */
public class ApacheHttpCoreClient implements HttpClient {
  private final LoggerShim logger;
  private final ESContext context;
  private CloseableHttpAsyncClient hcHttpClient;

  public ApacheHttpCoreClient(ESContext esContext, HttpConnectionSettings settings) {
    AccessController.doPrivileged((PrivilegedAction<Void>) () -> {

      CloseableHttpAsyncClient client = HttpAsyncClients.custom()
              .setDefaultRequestConfig(settings.toRequestConfig())
              .setMaxConnTotal(settings.getConnectionPoolSize())
              .build();
      this.hcHttpClient = settings.getValidateHttps() ? client : getNonValidatedHttpClient(settings);
      this.hcHttpClient.start();
      return null;
    });
    this.logger = esContext.logger(getClass());
    this.context = esContext;
    esContext.getShutDownObservable().addObserver((x, y) -> {
      try {
        hcHttpClient.close();
      } catch (IOException e) {
        logger.error("cannot shut down Apache HTTP Core client.. ", e);
      }
    });
  }

  private CloseableHttpAsyncClient getNonValidatedHttpClient(HttpConnectionSettings settings) {
    try {
      return HttpAsyncClients.custom()
              .setDefaultRequestConfig(settings.toRequestConfig())
              .setMaxConnTotal(settings.getConnectionPoolSize())
              .setSSLHostnameVerifier(new NoopHostnameVerifier()).setSSLContext(SSLContexts.custom().loadTrustMaterial(null, (X509Certificate[] chain, String authType) -> true).build())
              .build();
    } catch (KeyManagementException | NoSuchAlgorithmException | KeyStoreException e) {
      logger.error("cannot create non-validating Apache HTTP Core client.. ", e);
      return HttpAsyncClients.createDefault();
    }
  }

  @Override
  protected void finalize() throws Throwable {
    hcHttpClient.close();
  }

  @Override
  public CompletableFuture<RRHttpResponse> send(RRHttpRequest request) {

    CompletableFuture<HttpResponse> promise = new CompletableFuture<>();
    URI uri;
    HttpRequestBase hcRequest;
    try {
      if (request.getMethod() == HttpMethod.POST) {
        uri = new URIBuilder(request.getUrl().toASCIIString()).build();
        hcRequest = new HttpPost(uri);
        List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
        request.getQueryParams().entrySet().
            forEach(x -> urlParameters.add(new BasicNameValuePair(x.getKey(), x.getValue())));
        ((HttpPost) hcRequest).setEntity(new UrlEncodedFormEntity(urlParameters));

      } else {
        uri = new URIBuilder(request.getUrl().toASCIIString())
            .addParameters(
                request.getQueryParams().entrySet().stream()
                    .map(e -> new BasicNameValuePair(e.getKey(), e.getValue()))
                    .collect(Collectors.toList())
            ).build();
        hcRequest = new HttpGet(uri);
      }
    } catch (URISyntaxException e) {
      throw context.rorException(e.getClass().getSimpleName() + ": " + e.getMessage());
    } catch (UnsupportedEncodingException e) {
      throw context.rorException(e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    request.getHeaders().entrySet().forEach(e -> hcRequest.addHeader(e.getKey(), e.getValue()));

    AccessController.doPrivileged((PrivilegedAction<Void>) () -> {

      hcHttpClient.execute(hcRequest, new FutureCallback<HttpResponse>() {

        public void completed(final HttpResponse hcResponse) {
          int statusCode = hcResponse.getStatusLine().getStatusCode();
          logger.debug("HTTP REQ SUCCESS with status: " + statusCode + " " + request);
          promise.complete(hcResponse);
        }

        public void failed(final Exception ex) {
          logger.debug("HTTP REQ FAILED " + request);
          logger.info("HTTP client failed to connect: " + request + " reason: " + ex.getMessage());
          promise.completeExceptionally(ex);
        }

        public void cancelled() {
          promise.completeExceptionally(new RuntimeException("HTTP REQ CANCELLED: " + request));
        }
      });
      return null;
    });

    return promise.thenApply(hcResp -> new RRHttpResponse(hcResp.getStatusLine().getStatusCode(), () -> {
      try {
        return hcResp.getEntity().getContent();
      } catch (IOException e) {
        throw new RuntimeException("Cannot read content", e);
      }
    }));

  }

}
