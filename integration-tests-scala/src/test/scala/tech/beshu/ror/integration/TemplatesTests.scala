package tech.beshu.ror.integration

import com.dimafeng.testcontainers.ForAllTestContainer
import org.scalatest.WordSpec
import org.scalatest.Matchers._
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, ReadonlyRestEsCluster, ReadonlyRestEsClusterContainer}
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, TemplateManager}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.Version

import scala.collection.JavaConverters._

class TemplatesTests extends WordSpec with ForAllTestContainer {
  override val container: ReadonlyRestEsClusterContainer = ReadonlyRestEsCluster.createLocalClusterContainer(
    name = "ROR1",
    rorConfigFileName = "/templates/readonlyrest.yml",
    numberOfInstances = 1,
    TemplatesTests.nodeDataInitializer()
  )

  private lazy val adminTemplateManager = new TemplateManager(container.nodesContainers.head.adminClient)
  private lazy val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev1", "test"))
  private lazy val dev2TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev2", "test"))

  "A template API" when {
    "user is admin" should {
      "allow to get all templates" in {
        val templates = adminTemplateManager.getTemplates
        templates.getResponseCode should be (200)
        templates.getResults.asScala.keys.toList should contain only ("temp1", "temp2")
      }
    }
    "user is dev1" should {
      "allow to get only `temp1` templates" in {
        val templates = dev1TemplateManager.getTemplates
        templates.getResponseCode should be (200)
        templates.getResults.asScala.keys.toList should contain only "temp1"
      }
      "allow to create new template" in {
        val result = dev1TemplateManager.insertTemplate("new_template", TemplatesTests.templateExample(container.esVersion, "test1_index"))
        result.getResponseCode should be (200)

        adminTemplateManager.deleteTemplate("new_template")
      }
      "allow to remove `temp_to_remove` template" in {
        adminTemplateManager.insertTemplateAndWaitForIndexing("temp_to_remove", TemplatesTests.templateExample(container.esVersion,"test1_index"))

        val result = dev1TemplateManager.deleteTemplate("temp_to_remove")
        result.getResponseCode should be (200)
      }
    }
    "user is dev2" should {
      "forbid to get `temp1` template" in {
        val templates = dev2TemplateManager.getTemplate("temp1")
        templates.getResponseCode should be (401)
      }
      "forbid to create template for foreign index patten" in {
        val result = dev2TemplateManager.insertTemplate("new_template", TemplatesTests.templateExample(container.esVersion,"test1_index"))
        result.getResponseCode should be (401)
      }
      "forbid to remove foreign template" in {
        val result = dev2TemplateManager.deleteTemplate("temp1")
        result.getResponseCode should be (401)
      }
    }
    "user is dev3" should {
      "return empty list of templates" in {
        val dev3TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev3", "test"))

        val templates = dev3TemplateManager.getTemplates
        templates.getResponseCode should be (200)
        templates.getResults.size() should be (0)
      }
    }
  }
}

object TemplatesTests {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion: String, adminRestClient: RestClient) => {
    val templateManager = new TemplateManager(adminRestClient)
    templateManager.insertTemplateAndWaitForIndexing("temp1", templateExample(esVersion, "test1_*"))
    templateManager.insertTemplateAndWaitForIndexing("temp2", templateExample(esVersion, "test2_*"))

    val documentManager = new DocumentManager(adminRestClient)
    documentManager.insertDoc("/test1_index/test/1", "{\"hello\":\"world\"}")
    documentManager.insertDoc("/test2_index/test/1", "{\"hello\":\"world\"}")
    documentManager.insertDoc("/test3_index/test/1", "{\"hello\":\"world\"}")
  }

  private def templateExample(esVersion: String, indexPattern: String) = {
    if(Version.greaterOrEqualThan(esVersion, 7, 0, 0)) {
      s"""{"index_patterns":["$indexPattern"],"settings":{"number_of_shards":1},"mappings":{"properties":{"created_at":{"type":"date","format":"EEE MMM dd HH:mm:ss Z yyyy"}}}}"""
    } else if(Version.greaterOrEqualThan(esVersion, 6, 0, 0)) {
      s"""{"index_patterns":["$indexPattern"],"settings":{"number_of_shards":1},"mappings":{"_doc":{"properties":{"created_at":{"type":"date","format":"EEE MMM dd HH:mm:ss Z yyyy"}}}}}"""
    } else {
      s"""{"template":"$indexPattern","settings":{"number_of_shards":1},"mappings":{"doc":{"properties":{"created_at":{"type":"date","format":"EEE MMM dd HH:mm:ss Z yyyy"}}}}}"""
    }
  }
}
