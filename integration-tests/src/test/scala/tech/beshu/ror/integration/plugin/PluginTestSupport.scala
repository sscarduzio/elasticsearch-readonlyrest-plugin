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
package tech.beshu.ror.integration.plugin

import org.apache.commons.lang.StringEscapeUtils.escapeJava
import org.scalatest.{BeforeAndAfterAll, Suite}
import tech.beshu.ror.integration.suites.base.support.BasicSingleNodeEsClusterSupport
import tech.beshu.ror.utils.containers.providers._
import tech.beshu.ror.utils.containers.{EsContainer, EsWithRorPluginContainerCreator}
import tech.beshu.ror.utils.elasticsearch.ActionManagerJ
import tech.beshu.ror.utils.misc.Resources.getResourceContent

trait PluginTestSupport extends EsWithRorPluginContainerCreator with CallingEsDirectly {
  this: MultipleEsTargets =>
}

trait SingletonPluginTestSupport extends PluginTestSupport with BeforeAndAfterAll {
  this: Suite with BasicSingleNodeEsClusterSupport =>

  override lazy val targetEs: EsContainer = SingletonEsContainer.singleton.nodesContainers.head

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    SingletonEsContainer.removeAllIndices()
    updateConfig()
    nodeDataInitializer.foreach(_.initialize(targetEs.esVersion, SingletonEsContainer.adminClient))
  }

  private def updateConfig() = {
    val adminApiManager = new ActionManagerJ(SingletonEsContainer.adminClient)
    adminApiManager.actionPost(
      "_readonlyrest/admin/config",
      s"""{"settings": "${escapeJava(getResourceContent(rorConfigFileName))}"}"""
    )
  }
}