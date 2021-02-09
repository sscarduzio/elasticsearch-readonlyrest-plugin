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
import tech.beshu.ror.utils.elasticsearch.BaseManager.JSON
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, IndexManager, LegacyTemplateManager}
import tech.beshu.ror.utils.misc.Version

trait BaseTemplatesSuite
  extends BaseSingleNodeEsClusterTest
    with BeforeAndAfterEach {
  this: Suite with EsContainerCreator =>

  def rorContainer: EsClusterContainer

  protected lazy val adminTemplateManager = new LegacyTemplateManager(adminClient, targetEs.esVersion)
  protected lazy val adminIndexManager = new IndexManager(adminClient)
  protected lazy val adminDocumentManager = new DocumentManager(adminClient, targetEs.esVersion)

  protected def createIndexWithExampleDoc(documentManager: DocumentManager, index: String): Unit = {
    adminDocumentManager.createFirstDoc(index, ujson.read("""{"hello":"world"}""")).force()
  }

  protected def templateExample(indexPattern: String,
                                otherIndexPatterns: Set[String],
                                aliases: Set[String]): JSON = {
    putTemplateBodyJson(otherIndexPatterns + indexPattern, aliases)
  }

  protected def templateExample(indexPattern: String,
                                otherIndexPatterns: String*): JSON = {
    putTemplateBodyJson(otherIndexPatterns.toSet + indexPattern, Set.empty)
  }

  private def putTemplateBodyJson(indexPatterns: Set[String], aliases: Set[String]): JSON = {
    val esVersion = rorContainer.esVersion
    val allIndexPattern = indexPatterns.toList
    val patternsString = allIndexPattern.mkString("\"", "\",\"", "\"")
    if (Version.greaterOrEqualThan(esVersion, 7, 0, 0)) {
      ujson.read {
        s"""
           |{
           |  "index_patterns":[$patternsString],
           |  "aliases":{
           |    ${aliases.toList.map(a => s""""$a":{}""").mkString(",\n")}
           |  },
           |  "settings":{"number_of_shards":1},
           |  "mappings":{"properties":{"created_at":{"type":"date","format":"EEE MMM dd HH:mm:ss Z yyyy"}}}
           |}""".stripMargin
      }
    } else if (Version.greaterOrEqualThan(esVersion, 6, 0, 0)) {
      ujson.read {
        s"""
           |{
           |  "index_patterns":[$patternsString],
           |  "aliases":{
           |    ${aliases.toList.map(a => s""""$a":{}""").mkString(",\n")}
           |  },
           |  "settings":{"number_of_shards":1},
           |  "mappings":{"doc":{"properties":{"created_at":{"type":"date","format":"EEE MMM dd HH:mm:ss Z yyyy"}}}}
           ||}""".stripMargin
      }
    } else {
      if (allIndexPattern.size == 1) {
        ujson.read {
          s"""
             |{
             |  "template":"${allIndexPattern.head}",
             |  "aliases":{
             |    ${aliases.toList.map(a => s""""$a":{}""").mkString(",\n")}
             |  },
             |  "settings":{"number_of_shards":1},
             |  "mappings":{"doc":{"properties":{"created_at":{"type":"date","format":"EEE MMM dd HH:mm:ss Z yyyy"}}}}
             |}""".stripMargin
        }
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
    adminTemplateManager
      .getTemplates.force()
      .responseJson.obj.keys
      .foreach { template =>
        adminTemplateManager.deleteTemplate(template).force()
      }
  }

  private def truncateIndices(): Unit = {
    adminIndexManager
      .removeAllIndices()
      .force()
  }

  private def addControlTemplate(): Unit = {
    adminTemplateManager
      .insertTemplate(
        templateName = "control_one",
        indexPatterns = NonEmptyList.one("control_*"),
        aliases = Set("control")
      )
      .force()
  }
}
