package tech.beshu.ror.proxy.es.clients

import monix.execution.Scheduler
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.client._
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.threadpool.ThreadPool
import org.joor.Reflect._

class EsRestNodeClient(underlying: NodeClient,
                       esClient: RestHighLevelClientAdapter,
                       settings: Settings,
                       threadPool: ThreadPool)
                      (implicit scheduler: Scheduler)
  extends NodeClient(settings, threadPool) {

  private val customAdminClient = new AdminClient {
    override def cluster(): ClusterAdminClient = new HighLevelClientBasedClusterAdminClient(esClient)
    override def indices(): IndicesAdminClient = new HighLevelClientBasedIndicesAdminClient(esClient)
  }

  on(this).set("rorAdmin", customAdminClient)

}
