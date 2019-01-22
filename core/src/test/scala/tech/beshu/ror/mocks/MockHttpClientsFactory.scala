package tech.beshu.ror.mocks

import tech.beshu.ror.acl.factory.HttpClientsFactory
import tech.beshu.ror.acl.factory.HttpClientsFactory.HttpClient

object MockHttpClientsFactory extends HttpClientsFactory {

  override def create(config: HttpClientsFactory.Config): HttpClient =
    throw new IllegalStateException("Cannot use it. It's just a mock")
  override def shutdown(): Unit = {}
}

class MockHttpClientsFactoryWithFixedHttpClient(httpClient: HttpClient) extends HttpClientsFactory {
  override def create(config: HttpClientsFactory.Config): HttpClient = httpClient
  override def shutdown(): Unit = {}
}