package tech.beshu.ror.utils.elasticsearch;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.util.EntityUtils;
import tech.beshu.ror.utils.httpclient.RestClient;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class IndexManager {

  private final RestClient restClient;

  public IndexManager(RestClient restClient) {
    this.restClient = restClient;
  }

  public GetIndexResult get(String indexName) {
    try (CloseableHttpResponse response = restClient.execute(createGetIndexRequest(indexName))) {
      int statusCode = response.getStatusLine().getStatusCode();
      return statusCode != 200
          ? new GetIndexResult(statusCode, Sets.newHashSet())
          : new GetIndexResult(statusCode, getAliases(deserializeJsonBody(bodyFrom(response))));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }


  private HttpUriRequest createGetIndexRequest(String indexName) {
    return new HttpGet(restClient.from("/" + indexName));
  }

  private static Map<String, Object> deserializeJsonBody(String response) {
    Gson gson = new Gson();
    Type mapType = new TypeToken<HashMap<String, Object>>(){}.getType();
    return gson.fromJson(response, mapType);
  }

  private static Set<String> getAliases(Map<String, Object> result) {
    List<Object> responses = result.values().stream().collect(Collectors.toList());
    return ((Map<String, Object>) ((Map<String, Object>)responses.get(0)).get("aliases")).keySet();
  }

  private static String bodyFrom(HttpResponse r) {
    try {
      return EntityUtils.toString(r.getEntity());
    } catch (IOException e) {
      throw new RuntimeException("Cannot get string body", e);
    }
  }

  public static class GetIndexResult {

    private final Integer responseCode;
    private final ImmutableSet<String> aliases;

    GetIndexResult(Integer responseCode, Set<String> aliases) {
      this.responseCode = responseCode;
      this.aliases = ImmutableSet.copyOf(aliases);
    }

    public int getResponseCode() {
      return responseCode;
    }

    public ImmutableSet<String> getAliases() {
      return aliases;
    }
  }
}
