/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.integration.suites.base

import cats.data.NonEmptyList
import eu.timepit.refined.auto._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.{BaseEsClusterIntegrationTest, SingleClientSupport}
import tech.beshu.ror.integration.utils.{ESVersionSupportForAnyWordSpecLike, PluginTestSupport}
import tech.beshu.ror.utils.containers.EsClusterSettings.NodeType
import tech.beshu.ror.utils.containers.SecurityType.{RorSecurity, XPackSecurity}
import tech.beshu.ror.utils.containers._
import tech.beshu.ror.utils.containers.images.ReadonlyRestPlugin.Config.InternodeSsl
import tech.beshu.ror.utils.containers.images.domain.{Enabled, SourceFile}
import tech.beshu.ror.utils.containers.images.{ReadonlyRestPlugin, XpackSecurityPlugin}
import tech.beshu.ror.utils.elasticsearch._
import tech.beshu.ror.utils.misc.CustomScalaTestMatchers
import tech.beshu.ror.utils.misc.Resources.getResourceContent

trait XpackClusterWithRorNodesAndInternodeSslSuite
  extends AnyWordSpec
    with BaseEsClusterIntegrationTest
    with PluginTestSupport
    with ESVersionSupportForAnyWordSpecLike
    with SingleClientSupport
    with BeforeAndAfterAll
    with Eventually
    with CustomScalaTestMatchers {

  def rorConfigPath: String

  override implicit val rorConfigFileName: String =
    if (executedOn(allEs6xBelowEs65x)) {
      "/xpack_cluster_with_ror_nodes_and_internode_ssl/readonlyrest_es60x-es65x.yml"
    } else {
      rorConfigPath
    }

  override def clusterContainer: EsClusterContainer = generalClusterContainer

  override def targetEs: EsContainer = generalClusterContainer.nodes.head

  lazy val generalClusterContainer: EsClusterContainer = createLocalClusterContainer {
    if (executedOn(allEs6xExceptEs67x)) {
      EsClusterSettings.create(
        clusterName = "ror_cluster",
        numberOfInstances = 3,
        securityType = RorSecurity(ReadonlyRestPlugin.Config.Attributes.default.copy(
          rorConfigFileName = rorConfigFileName,
          internodeSsl = Enabled.Yes(InternodeSsl.Ror(SourceFile.EsFile))
        ))
      )
    } else {
      EsClusterSettings.createMixedCluster(
        clusterName = "ror_xpack_cluster",
        nodeTypes = NonEmptyList.of(
          NodeType(
            securityType = RorSecurity(ReadonlyRestPlugin.Config.Attributes.default.copy(
              rorConfigFileName = rorConfigFileName,
              internodeSsl = Enabled.Yes(InternodeSsl.Ror(SourceFile.EsFile))
            )),
            numberOfInstances = 1
          ),
          NodeType(
            securityType = XPackSecurity(XpackSecurityPlugin.Config.Attributes.default.copy(
              restSslEnabled = true,
              internodeSslEnabled = true
            )),
            numberOfInstances = 2
          )
        )
      )
    }
  }

  "Health check works" in {
    val rorClusterAdminStateManager = new CatManager(clusterContainer.nodes.head.adminClient, esVersion = esVersionUsed)

    val response = rorClusterAdminStateManager.healthCheck()

    response should have statusCode 200
  }
  "ROR config reload can be done" in {
    val rorApiManager = new RorApiManager(clusterContainer.nodes.head.adminClient, esVersion = esVersionUsed)

    val updateResult = rorApiManager
      .updateRorInIndexConfig(getResourceContent("/xpack_cluster_with_ror_nodes_and_internode_ssl/readonlyrest_update.yml"))

    updateResult should have statusCode 200
    updateResult.responseJson("status").str should be("ok")

    val indexManager = new IndexManager(basicAuthClient("user1", "test"), esVersion = esVersionUsed)
    indexManager.createIndex("test").force()

    val getIndexResult = indexManager.getIndex("test")

    getIndexResult should have statusCode 200
    getIndexResult.indicesAndAliases.keys.toList should be(List("test"))
  }
  "Field caps request works" in {
    val documentManager = new DocumentManager(clusterContainer.nodes.head.adminClient, esVersion = esVersionUsed)
    documentManager.createDoc("user2_index", 1, ujson.read("""{ "data1": 1, "data2": 2 }""")).force()

    eventually {
      val searchManager = new SearchManager(basicAuthClient("user2", "test"), esVersionUsed)
      val result = searchManager.fieldCaps(indices = "user2_index" :: Nil, fields = "data1" :: Nil)

      result should have statusCode 200
    }
  }
}
