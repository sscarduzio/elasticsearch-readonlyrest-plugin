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
import com.typesafe.scalalogging.LazyLogging
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Suite}
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.integration.utils.ESVersionSupport
import tech.beshu.ror.utils.containers.EsContainerCreator
import tech.beshu.ror.utils.elasticsearch._
import tech.beshu.ror.utils.misc.ScalaUtils.waitForCondition
import tech.beshu.ror.utils.misc.Version

trait BaseTemplatesSuite
  extends BaseSingleNodeEsClusterTest
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with ESVersionSupport
    with LazyLogging {
  this: Suite with EsContainerCreator =>

  private lazy val adminLegacyTemplateManager = new LegacyTemplateManager(rorAdminClient, esVersionUsed)
  private lazy val adminIndexTemplateManager = new IndexTemplateManager(rorAdminClient, esVersionUsed)
  private lazy val adminComponentTemplateManager = new ComponentTemplateManager(rorAdminClient, esVersionUsed)
  private lazy val adminIndexManager = new IndexManager(rorAdminClient, esVersionUsed)
  protected lazy val adminDocumentManager = new DocumentManager(rorAdminClient, esVersionUsed)

  private var originLegacyTemplateNames: List[String] = List.empty
  private var originIndexTemplateNames: List[String] = List.empty
  private var originComponentTemplateNames: List[String] = List.empty

  protected def createIndexWithExampleDoc(documentManager: DocumentManager, index: String): Unit = {
    adminDocumentManager.createFirstDoc(index, ujson.read("""{"hello":"world"}""")).force()
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    originLegacyTemplateNames = adminLegacyTemplateManager
      .getTemplates.force()
      .templates
      .map(_.name)
    if (doesSupportIndexTemplates) {
      logger.info(s"Found origin Legacy Templates: [${originLegacyTemplateNames.mkString(",")}]")
      originIndexTemplateNames = adminIndexTemplateManager
        .getTemplates.force()
        .templates
        .map(_.name)
    }
    logger.info(s"Found origin Index Templates: [${originIndexTemplateNames.mkString(",")}]")
    if (doesSupportComponentTemplates) {
      originComponentTemplateNames = adminComponentTemplateManager
        .getTemplates.force()
        .templates
        .map(_.name)
      logger.info(s"Found origin Component Templates: [${originComponentTemplateNames.mkString(",")}]")
    }
  }

  override protected def afterAll(): Unit = {
    truncateDataCreatedDuringTest()
    super.afterAll()
  }

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    addControlData()
  }

  override protected def afterEach(): Unit = {
    truncateDataCreatedDuringTest()
    super.afterEach()
  }

  private def addControlData(): Unit = {
    addControlLegacyTemplate()
    if (doesSupportIndexTemplates) addControlIndexTemplate()
    if (doesSupportComponentTemplates) addControlComponentTemplate()
  }

  private def truncateDataCreatedDuringTest(): Unit = {
    truncateLegacyTemplates()
    if (doesSupportIndexTemplates) truncateIndexTemplates()
    if (doesSupportComponentTemplates) truncateComponentTemplates()
    truncateIndices()
  }

  private def truncateLegacyTemplates(): Unit = {
    adminLegacyTemplateManager
      .getTemplates.force()
      .templates
      .foreach { template =>
        if (!originLegacyTemplateNames.contains(template.name))
          adminLegacyTemplateManager.deleteTemplate(template.name).force()
      }
    waitForCondition("Waiting for removing all Legacy Templates") {
      adminLegacyTemplateManager
        .getTemplates.force().templates.map(_.name)
        .diff(originLegacyTemplateNames)
        .isEmpty
    }
  }

  private def truncateIndexTemplates(): Unit = {
    adminIndexTemplateManager
      .getTemplates.force()
      .templates
      .foreach { template =>
        if (!originIndexTemplateNames.contains(template.name))
          adminIndexTemplateManager.deleteTemplate(template.name).force()
      }
    waitForCondition("Waiting for removing all Index Templates") {
      adminIndexTemplateManager
        .getTemplates.force().templates.map(_.name)
        .diff(originIndexTemplateNames)
        .isEmpty
    }
  }

  private def truncateComponentTemplates(): Unit = {
    adminComponentTemplateManager
      .getTemplates.force()
      .templates
      .foreach { template =>
        if (!originComponentTemplateNames.contains(template.name))
          adminComponentTemplateManager.deleteTemplate(template.name).force()
      }
    waitForCondition("Waiting for removing all Component Templates") {
      adminComponentTemplateManager
        .getTemplates.force().templates.map(_.name)
        .diff(originComponentTemplateNames)
        .isEmpty
    }
  }

  private def truncateIndices(): Unit = {
    adminIndexManager
      .removeAllIndices()
      .force()
  }

  private def addControlLegacyTemplate(): Unit = {
    adminLegacyTemplateManager
      .putTemplateAndWaitForIndexing(
        templateName = "control_one",
        indexPatterns = NonEmptyList.one("control_one_*"),
        aliases = Set("control")
      )
  }

  private def addControlIndexTemplate(): Unit = {
    adminIndexTemplateManager
      .putTemplateAndWaitForIndexing(
        templateName = "control_two",
        indexPatterns = NonEmptyList.one("control_two_*"),
        aliases = Set("control")
      )
  }

  private def addControlComponentTemplate(): Unit = {
    adminComponentTemplateManager
      .putTemplateAndWaitForIndexing(
        templateName = "control_three",
        aliases = Set("control")
      )
  }

  protected def doesSupportComponentTemplates: Boolean = {
    Version.greaterOrEqualThan(esVersionUsed, 7, 9, 0)
  }

  protected def doesSupportIndexTemplates: Boolean = {
    Version.greaterOrEqualThan(esVersionUsed, 7, 8, 0)
  }
}
