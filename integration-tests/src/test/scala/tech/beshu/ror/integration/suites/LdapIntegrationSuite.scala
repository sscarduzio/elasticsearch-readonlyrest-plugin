package tech.beshu.ror.integration.suites

import org.scalatest.{Matchers, WordSpec}
import tech.beshu.ror.integration.suites.base.support.{BaseEsClusterIntegrationTest, SingleClientSupport}
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsClusterContainer, EsClusterSettings, EsContainerCreator}
import tech.beshu.ror.utils.containers.dependencies.ldap
import tech.beshu.ror.utils.elasticsearch.ElasticsearchTweetsInitializer
import tech.beshu.ror.utils.httpclient.RestClient

trait LdapIntegrationSuite
  extends WordSpec
    with BaseEsClusterIntegrationTest
    with SingleClientSupport
    with Matchers {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/ldap_integration/readonlyrest.yml"

  override lazy val targetEs = container.nodes.head

  override lazy val clusterContainer: EsClusterContainer = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1",
      dependentServicesContainers = List(
        ldap(name = "LDAP1", ldapInitScript = "/ldap_integration/ldap.ldif"),
        ldap(name = "LDAP2", ldapInitScript = "/ldap_integration/ldap.ldif")
      ),
      nodeDataInitializer = LdapIntegrationSuite.nodeDataInitializer()
    )
  )

}

object LdapIntegrationSuite {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    new ElasticsearchTweetsInitializer().initialize(adminRestClient)
  }
}