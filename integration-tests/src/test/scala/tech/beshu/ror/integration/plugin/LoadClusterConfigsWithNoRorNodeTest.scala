package tech.beshu.ror.integration.plugin

import java.util

import cats.data.NonEmptyList
import org.scalatest.Matchers.{be, contain, _}
import org.scalatest.{BeforeAndAfterEach, Entry, WordSpec}
import tech.beshu.ror.integration.suites.AdminApiWithDefaultRorIndexSuite
import tech.beshu.ror.integration.suites.base.support.{BaseIntegrationTest, MultipleClientsSupport}
import tech.beshu.ror.utils.containers._
import tech.beshu.ror.utils.elasticsearch.ActionManagerJ
import tech.beshu.ror.utils.misc.Resources.getResourceContent

final class LoadClusterConfigsWithNoRorNodeTest
  extends WordSpec
    with BeforeAndAfterEach
    with PluginTestSupport
    with BaseIntegrationTest
    with AdminApiWithDefaultRorIndexSuite
    with MultipleClientsSupport {
  this: EsContainerCreator =>
  private object EsWithRorCreator extends EsWithRorPluginContainerCreator
  private object EsWithoutRorCreator extends EsWithoutRorPluginContainerCreator

  private def creteEsWithAndWithoutRorCreator: Stream[EsContainerCreator] =
    Stream.cons(
      EsWithRorCreator,
      Stream.cons(
        EsWithoutRorCreator,
        creteEsWithAndWithoutRorCreator
      )
    )

  private val iterator = creteEsWithAndWithoutRorCreator.iterator

  override def create(name: String, nodeNames: NonEmptyList[String], clusterSettings: EsClusterSettings, startedClusterDependencies: StartedClusterDependencies): EsContainer = {
    val creator = iterator.next()
    println(s"creator: $creator")
    creator.create(name, nodeNames, clusterSettings, startedClusterDependencies)
  }

  private lazy val ror1WithIndexConfigAdminActionManager = new ActionManagerJ(clients.head.adminClient)

  "in-index config is the same as current one" in {
    val result = ror1WithIndexConfigAdminActionManager.actionGet("_readonlyrest/admin/config/load")
    result.getResponseCode should be(200)
    result.getResponseJsonMap.get("clusterName") should be("ROR1")
    result.getResponseJsonMap.get("failures").asInstanceOf[util.Collection[Nothing]] should have size 1
    val javaResponses = result.getResponseJsonMap.get("responses").asInstanceOf[util.List[util.Map[String, String]]]
    val jnode1 = javaResponses.get(0)
    jnode1 should contain key "nodeId"
    jnode1 should contain(Entry("type", "IndexConfig"))
    jnode1.get("config") should be(getResourceContent("/admin_api/readonlyrest_index.yml"))
  }
}
