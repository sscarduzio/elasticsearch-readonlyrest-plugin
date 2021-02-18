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
import org.scalatest.{BeforeAndAfterEach, Suite}
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.utils.containers.{EsClusterContainer, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, IndexManager, LegacyTemplateManager, TemplateManager}

trait BaseTemplatesSuite
  extends BaseSingleNodeEsClusterTest
    with BeforeAndAfterEach {
  this: Suite with EsContainerCreator =>

  def rorContainer: EsClusterContainer

  private lazy val adminLegacyTemplateManager = new LegacyTemplateManager(adminClient, targetEs.esVersion)
  private lazy val adminTemplateManager = new TemplateManager(adminClient, targetEs.esVersion)
  private lazy val adminIndexManager = new IndexManager(adminClient)
  protected lazy val adminDocumentManager = new DocumentManager(adminClient, targetEs.esVersion)

  protected def createIndexWithExampleDoc(documentManager: DocumentManager, index: String): Unit = {
    adminDocumentManager.createFirstDoc(index, ujson.read("""{"hello":"world"}""")).force()
  }

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    truncateLegacyTemplates()
    truncateTemplates()
    truncateIndices()
    addControlLegacyTemplate()
    addControlTemplate()
  }

  private def truncateLegacyTemplates(): Unit = {
    adminLegacyTemplateManager
      .getTemplates.force()
      .templates
      .foreach { template =>
        adminLegacyTemplateManager.deleteTemplate(template.name).force()
      }
  }

  private def truncateTemplates(): Unit = {
    adminTemplateManager
      .getTemplates.force()
      .templates
      .foreach { template =>
        adminTemplateManager.deleteTemplate(template.name).force()
      }
  }

  private def truncateIndices(): Unit = {
    adminIndexManager
      .removeAllIndices()
      .force()
  }

  private def addControlLegacyTemplate(): Unit = {
    adminLegacyTemplateManager
      .insertTemplate(
        templateName = "control_one",
        indexPatterns = NonEmptyList.one("control_*"),
        aliases = Set("control")
      )
      .force()
  }

  private def addControlTemplate(): Unit = {
    adminTemplateManager
      .insertTemplate(
        templateName = "control_two",
        indexPatterns = NonEmptyList.one("control_*"),
        aliases = Set("control")
      )
      .force()
  }
}
