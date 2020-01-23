package tech.beshu.ror.utils.containers

import tech.beshu.ror.utils.httpclient.RestClient

trait ElasticsearchNodeDataInitializer {
  def initialize(esVersion: String, adminRestClient: RestClient): Unit
}

object NoOpElasticsearchNodeDataInitializer extends ElasticsearchNodeDataInitializer {
  override def initialize(esVersion: String, adminRestClient: RestClient): Unit = {}
}