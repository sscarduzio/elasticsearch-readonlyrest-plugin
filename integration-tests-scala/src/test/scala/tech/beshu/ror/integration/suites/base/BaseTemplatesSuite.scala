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

import com.dimafeng.testcontainers.Container
import org.scalatest.{BeforeAndAfterEach, Suite}
import tech.beshu.ror.integration.suites.base.support.{BaseIntegrationTest, SingleClientSupport}
import tech.beshu.ror.utils.containers.generic._
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, IndexManager, TemplateManager}
import tech.beshu.ror.utils.misc.Version

trait BaseTemplatesSuite
  extends BaseIntegrationTest
    with SingleClientSupport
    with BeforeAndAfterEach {
  this: Suite with EsContainerCreator =>

  override lazy val targetEs = rorContainer.nodesContainers.head

  def rorContainer: EsClusterContainer

  override lazy val container: Container = rorContainer

  protected lazy val adminTemplateManager = new TemplateManager(rorContainer.nodesContainers.head.adminClient)
  protected lazy val adminDocumentManager = new DocumentManager(rorContainer.nodesContainers.head.adminClient)

  protected def createIndexWithExampleDoc(documentManager: DocumentManager, index: String): Unit = {
    val esVersion = rorContainer.esVersion
    if (Version.greaterOrEqualThan(esVersion, 7, 0, 0)) {
      adminDocumentManager.createDoc(s"/$index/_doc/1", "{\"hello\":\"world\"}")
    } else {
      adminDocumentManager.createDoc(s"/$index/doc/1", "{\"hello\":\"world\"}")
    }
  }

  protected def templateExample(indexPattern: String, otherIndexPatterns: String*): String = {
    val esVersion = rorContainer.esVersion
    val allIndexPattern = indexPattern :: otherIndexPatterns.toList
    val patternsString = allIndexPattern.mkString("\"", "\",\"", "\"")
    if (Version.greaterOrEqualThan(esVersion, 7, 0, 0)) {
      s"""{"index_patterns":[$patternsString],"settings":{"number_of_shards":1},"mappings":{"properties":{"created_at":{"type":"date","format":"EEE MMM dd HH:mm:ss Z yyyy"}}}}"""
    } else if (Version.greaterOrEqualThan(esVersion, 6, 0, 0)) {
      s"""{"index_patterns":[$patternsString],"settings":{"number_of_shards":1},"mappings":{"doc":{"properties":{"created_at":{"type":"date","format":"EEE MMM dd HH:mm:ss Z yyyy"}}}}}"""
    } else {
      if(otherIndexPatterns.isEmpty) {
        s"""{"template":"$indexPattern","settings":{"number_of_shards":1},"mappings":{"doc":{"properties":{"created_at":{"type":"date","format":"EEE MMM dd HH:mm:ss Z yyyy"}}}}}"""
      } else {
        throw new IllegalArgumentException("Cannot create template with more than one index pattern for the ES version < 6.0.0")
      }
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
    if(templates.responseCode != 200) throw new IllegalStateException("Cannot get all templates by admin")
    templates
      .responseJson.obj.keys
      .foreach { template =>
        val deleteTemplateResult = adminTemplateManager.deleteTemplate(template)
        if (deleteTemplateResult.responseCode != 200) throw new IllegalStateException(s"Admin cannot delete '$template' template")
      }
  }

  private def truncateIndices(): Unit = {
    val indicesManager = new IndexManager(rorContainer.nodesContainers.head.adminClient)
    if(indicesManager.removeAll.responseCode != 200) {
      throw new IllegalStateException("Admin cannot remove all indices")
    }
  }

  private def addControlTemplate(): Unit = {
    val response = adminTemplateManager.insertTemplate("control_one", templateExample("control_*"))
    if (response.responseCode != 200) {
      throw new IllegalStateException("Cannot add control template")
    }
  }
}
