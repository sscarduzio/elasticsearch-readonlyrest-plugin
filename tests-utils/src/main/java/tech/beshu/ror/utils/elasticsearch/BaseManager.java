package tech.beshu.ror.utils.elasticsearch;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import tech.beshu.ror.utils.httpclient.RestClient;
import tech.beshu.ror.utils.misc.HttpResponseHelper;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static tech.beshu.ror.utils.misc.HttpResponseHelper.stringBodyFrom;

public abstract class BaseManager {

  protected final RestClient restClient;

  protected BaseManager(RestClient restClient) {
    this.restClient = restClient;
  }

  protected <T> T call(HttpUriRequest request, Function<HttpResponse, T> resultFromResponse) {
    additionalHeaders().forEach(request::addHeader);
    try (CloseableHttpResponse response = restClient.execute(request)) {
      return resultFromResponse.apply(response);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  protected Map<String, String> additionalHeaders() {
    return Maps.newHashMap();
  }

  public static class SimpleResponse {

    private final Integer responseCode;

    SimpleResponse(HttpResponse response) {
      this.responseCode = response.getStatusLine().getStatusCode();
    }

    public int getResponseCode() {
      return responseCode;
    }
  }

  public static class TextLinesResponse extends SimpleResponse {

    private final List<String> results;
    private final String body;

    TextLinesResponse(HttpResponse response) {
      super(response);
      this.body = stringBodyFrom(response);
      this.results = getResponseCode() != 200
          ? Lists.newArrayList()
          : linesFrom(body);
    }

    public List<String> getResults() {
      return results;
    }

    private List<String> linesFrom(String body) {
      String[] lines = body.split(System.getProperty("line.separator"));
      if(lines.length == 0) return Lists.newArrayList();
      else if (lines.length == 1 && lines[0].isEmpty()) return Lists.newArrayList();
      else return Lists.newArrayList(lines);
    }
  }

  public static class JsonResponse extends SimpleResponse {

    private final Map<String, Object> responseJson;
    private final String body;

    JsonResponse(HttpResponse response) {
      this(response, false);
    }

    JsonResponse(HttpResponse response, Boolean withJsonError) {
      super(response);
      this.body = stringBodyFrom(response);
      this.responseJson = getResponseCode() != 200 || !withJsonError
          ? Maps.newHashMap()
          : HttpResponseHelper.deserializeJsonBody(body);
    }

    public Map<String, Object> getResponseJson() {
      return responseJson;
    }

    public String getRawBody() { return body; }
  }
}
