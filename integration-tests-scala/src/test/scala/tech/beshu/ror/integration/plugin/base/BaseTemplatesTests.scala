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
package tech.beshu.ror.integration.plugin.base

import com.dimafeng.testcontainers.{Container, ForAllTestContainer}
import org.scalatest.{BeforeAndAfterEach, Suite}
import tech.beshu.ror.utils.containers.ReadonlyRestEsClusterContainer
import tech.beshu.ror.utils.elasticsearch.{DocumentManagerJ, IndexManagerJ, TemplateManagerJ}
import tech.beshu.ror.utils.misc.Version

import scala.collection.JavaConverters._

trait BaseTemplatesTests extends ForAllTestContainer with BeforeAndAfterEach {
  this: Suite =>

  def rorContainer: ReadonlyRestEsClusterContainer
  override lazy val container: Container = rorContainer

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
