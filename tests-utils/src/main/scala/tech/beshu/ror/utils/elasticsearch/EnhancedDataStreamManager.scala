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
package tech.beshu.ror.utils.elasticsearch

import cats.data.NonEmptyList
import tech.beshu.ror.utils.elasticsearch.IndexManager.AliasAction

import java.time.Instant
import java.util.UUID

class EnhancedDataStreamManager(dataStreamManager: DataStreamManager,
                                documentManager: DocumentManager,
                                indexManager: IndexManager,
                                indexTemplateManager: IndexTemplateManager) {

  def createDataStream(name: String): Unit = {
    indexTemplateManager
      .createTemplate(
        templateName = s"index-template-${UUID.randomUUID().toString}",
        body = indexTemplate(name)
      )
      .force()

    dataStreamManager.createDataStream(name).force()
  }

  def createDocInDataStream(name: String, message: String): Unit = {
    val doc = documentJson(message)
    documentManager.createDocWithGeneratedId(name, doc).force()
  }

  def createDocsInDataStream(name: String, messages: NonEmptyList[String], rolloverAfterEachDoc: Boolean): Unit = {
    messages.tail.foldLeft(createDocInDataStream(name = name, message = messages.head)) { case (_, message: String) =>
      if (rolloverAfterEachDoc) rolloverDataStream(name)
      createDocInDataStream(name = name, message = message)
    }
  }

  def addAlias(name: String, alias: String): Unit = {
    addAliases(name, NonEmptyList.one(alias))
  }

  def addAliases(name: String, aliases: NonEmptyList[String]): Unit = {
    val actions = aliases.map(alias => AliasAction.Add(index = name, alias = alias))
    indexManager
      .updateAliases(
        actions.head,
        actions.tail*
      )
      .force()
  }

  def rolloverDataStream(name: String): Unit = {
    indexManager.rollover(name).force()
  }

  private def indexTemplate(dataStreamName: String) = ujson.read(
    s"""
       |{
       |  "index_patterns": ["$dataStreamName*"],
       |  "data_stream": { },
       |  "priority": 500,
       |  "template": {
       |    "mappings": {
       |      "properties": {
       |        "@timestamp": {
       |          "type": "date",
       |          "format": "date_optional_time||epoch_millis"
       |        },
       |        "message": {
       |          "type": "wildcard"
       |        }
       |      }
       |    }
       |  }
       |}
       |""".stripMargin
  )

  private def documentJson(message: String): ujson.Value =
    ujson.Obj(
      "@timestamp" -> format(Instant.now()),
      "message" -> message
    )

  private def format(instant: Instant) = instant.toString
}
