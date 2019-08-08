package tech.beshu.ror.es.rradmin

import org.elasticsearch.action.ActionRequestBuilder
import org.elasticsearch.client.ElasticsearchClient

class RRAdminRequestBuilder(client: ElasticsearchClient, action: RRAdminAction)
  extends ActionRequestBuilder[RRAdminRequest, RRAdminResponse](client, action, new RRAdminRequest)
