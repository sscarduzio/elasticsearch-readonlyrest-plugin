package tech.beshu.ror.proxy.es.proxyaction;

import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.http.HttpChannel;
import org.elasticsearch.http.HttpRequest;
import org.elasticsearch.rest.RestRequest;

import java.util.List;
import java.util.Map;

public class ProxyRestRequest extends RestRequest {

  public ProxyRestRequest(NamedXContentRegistry xContentRegistry,
                          Map<String, String> params,
                          String path,
                          Map<String, List<String>> headers,
                          HttpRequest httpRequest,
                          HttpChannel httpChannel) {
    super(xContentRegistry, params, path, headers, httpRequest, httpChannel);
  }
}
