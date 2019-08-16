package tech.beshu.ror.utils.elasticsearch;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import tech.beshu.ror.utils.httpclient.RestClient;

import java.io.IOException;

public class ClusterStateManager {

  private final RestClient restClient;

  public ClusterStateManager(RestClient restClient) {
    this.restClient = restClient;
  }

  public HealthCheckResponse healthCheck() {
    try (CloseableHttpResponse response = restClient.execute(createHealthCheckRequest())) {
      return new HealthCheckResponse(response.getStatusLine().getStatusCode());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private HttpUriRequest createHealthCheckRequest() {
    return new HttpGet(restClient.from("/_cat/health"));
  }

  public static class HealthCheckResponse {

    private final Integer responseCode;

    HealthCheckResponse(Integer responseCode) {
      this.responseCode = responseCode;
    }

    public int getResponseCode() {
      return responseCode;
    }
  }
}
