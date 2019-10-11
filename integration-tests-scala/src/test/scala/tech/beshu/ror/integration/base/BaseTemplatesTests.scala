package tech.beshu.ror.integration.base

import com.dimafeng.testcontainers.{Container, ForAllTestContainer}
import org.scalatest.{BeforeAndAfterEach, Suite}
import tech.beshu.ror.utils.containers.ReadonlyRestEsClusterContainer
import tech.beshu.ror.utils.elasticsearch.{DocumentManagerJ, IndexManagerJ, TemplateManagerJ}
import tech.beshu.ror.utils.misc.Version

import scala.collection.JavaConverters._

trait BaseTemplatesTests extends ForAllTestContainer with BeforeAndAfterEach {
  this: Suite =>

  def rorContainer: ReadonlyRestEsClusterContainer
  override val container: Container = rorContainer

  protected lazy val adminTemplateManager = new TemplateManagerJ(rorContainer.nodesContainers.head.adminClient)
  protected lazy val adminDocumentManager = new DocumentManagerJ(rorContainer.nodesContainers.head.adminClient)

  protected def createIndexWithExampleDoc(documentManager: DocumentManagerJ, index: String): Unit = {
    val esVersion = rorContainer.esVersion
    if(Version.greaterOrEqualThan(esVersion, 7, 0, 0)) {
      adminDocumentManager.insertDocAndWaitForRefresh(s"/$index/_doc/1", "{\"hello\":\"world\"}")
    } else {
      adminDocumentManager.insertDocAndWaitForRefresh(s"/$index/doc/1", "{\"hello\":\"world\"}")
    }
  }

  protected def templateExample(indexPattern: String): String = {
    val esVersion = rorContainer.esVersion
    if(Version.greaterOrEqualThan(esVersion, 7, 0, 0)) {
      s"""{"index_patterns":["$indexPattern"],"settings":{"number_of_shards":1},"mappings":{"properties":{"created_at":{"type":"date","format":"EEE MMM dd HH:mm:ss Z yyyy"}}}}"""
    } else if(Version.greaterOrEqualThan(esVersion, 6, 1, 0)) {
      s"""{"index_patterns":["$indexPattern"],"settings":{"number_of_shards":1},"mappings":{"doc":{"properties":{"created_at":{"type":"date","format":"EEE MMM dd HH:mm:ss Z yyyy"}}}}}"""
    } else {
      s"""{"template":"$indexPattern","settings":{"number_of_shards":1},"mappings":{"doc":{"properties":{"created_at":{"type":"date","format":"EEE MMM dd HH:mm:ss Z yyyy"}}}}}"""
    }
  }

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    truncateTemplates()
    truncateIndices()
    addControlTemplate()
  }

  private def truncateTemplates(): Unit = {
    val templates = adminTemplateManager.getTemplates
    if(templates.getResponseCode != 200) throw new IllegalStateException("Cannot get all templates by admin")
    templates
      .getResponseJsonMap.keySet().asScala
      .foreach { template =>
        val deleteTemplateResult = adminTemplateManager.deleteTemplate(template)
        if(deleteTemplateResult.getResponseCode != 200) throw new IllegalStateException(s"Admin cannot delete '$template' template")
      }
  }

  private def truncateIndices(): Unit = {
    val indicesManager = new IndexManagerJ(rorContainer.nodesContainers.head.adminClient)
    if(indicesManager.removeAll().getResponseCode != 200) {
      throw new IllegalStateException("Admin cannot remove all indices")
    }
  }

  private def addControlTemplate(): Unit = {
    val response = adminTemplateManager.insertTemplate("control_one", templateExample("control_*"))
    if(response.getResponseCode != 200) {
      throw new IllegalStateException("Cannot add control template")
    }
  }
}
