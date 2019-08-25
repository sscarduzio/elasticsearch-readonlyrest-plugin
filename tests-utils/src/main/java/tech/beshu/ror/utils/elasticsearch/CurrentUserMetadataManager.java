package tech.beshu.ror.utils.elasticsearch;

import org.apache.http.client.methods.HttpGet;
import tech.beshu.ror.utils.httpclient.RestClient;

public class CurrentUserMetadataManager extends BaseManager {

  public CurrentUserMetadataManager(RestClient restClient) {
    super(restClient);
  }

  public JsonResponse fetchMetadata() {
    return call(createSearchRequest(), JsonResponse::new);
  }

  private HttpGet createSearchRequest() {
    return new HttpGet(restClient.from("/_readonlyrest/metadata/current_user"));
  }

}

