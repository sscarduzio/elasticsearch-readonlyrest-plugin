package org.elasticsearch.plugin.readonlyrest.es;

import org.elasticsearch.action.admin.indices.template.put.PutIndexTemplateAction;
import org.elasticsearch.action.admin.indices.template.put.PutIndexTemplateRequestBuilder;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentType;

/**
 * Created by sscarduzio on 14/06/2017.
 */
public class RequestContextIndexer {
  public RequestContextIndexer(Client client) {
    client.admin().indices().putTemplate(
      new PutIndexTemplateRequestBuilder(
        client.admin().indices(),
        PutIndexTemplateAction.INSTANCE,
        "template_readonlyrest-audit"
      )
        .setTemplate("readonlyrest-audit-*")
        .setSettings(
          Settings.builder()
            .put("number_of_shards", 10)
            .put("number_of_replicas", 1)
          .build()
        )
        .addMapping("document", "", XContentType.JSON)

    ).get();

  }
}
