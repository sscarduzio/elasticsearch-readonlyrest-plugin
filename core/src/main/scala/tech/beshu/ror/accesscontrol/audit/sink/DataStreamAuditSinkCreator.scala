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
package tech.beshu.ror.accesscontrol.audit.sink

import cats.data.NonEmptyList
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.audit.sink.DataStreamAuditSinkCreator.DataStreamSettings
import tech.beshu.ror.accesscontrol.domain.{DataStreamName, TemplateName}
import tech.beshu.ror.audit.instances.FieldType
import tech.beshu.ror.es.DataStreamService
import tech.beshu.ror.implicits.*
import ujson.Value

final class DataStreamAuditSinkCreator(services: NonEmptyList[DataStreamService]) extends Logging {

  def createIfNotExists(streamSettings: DataStreamSettings): Task[Unit] = {
    services.toList.traverse(createIfNotExists(_, streamSettings)).map((_: List[Unit]) => ())
  }

  private def createIfNotExists(service: DataStreamService, settings: DataStreamSettings): Task[Unit] = {
    service
      .checkDataStreamExists(settings.dataStreamName)
      .flatMap {
        case true =>
          Task.delay(logger.info(s"Data stream ${settings.dataStreamName.show} already exists"))
        case false =>
          setupDataStream(service, settings)
      }
  }

  private def setupDataStream(service: DataStreamService, settings: DataStreamSettings): Task[Unit] = {
    for {
      _ <- Task.delay(logger.info(s"Trying to setup ROR audit sink with data stream ${settings.dataStreamName.show}.."))
      _ <- service.createIndexLifecyclePolicy(settings.lifecyclePolicy, settings.lifecyclePolicyJson(service.capabilities.ilmMaxPrimaryShardSize))
      _ <- service.createComponentTemplateForMappings(settings.componentTemplateMappingsId, settings.mappings, settings.mappingsMetadata)
      _ <- service.createComponentTemplateForIndex(settings.componentTemplateSettingsId, settings.lifecyclePolicy, settings.indexSettingsMetadata)
      _ <- service.createIndexTemplate(settings.indexTemplate, settings.dataStreamName, NonEmptyList.of(settings.componentTemplateMappingsId, settings.componentTemplateSettingsId), settings.indexTemplateMetadata)
      _ <- service.createDataStream(settings.dataStreamName)
      _ <- Task.delay(logger.info(s"ROR audit data stream ${settings.dataStreamName.show} created."))
    } yield ()
  }
}


object DataStreamAuditSinkCreator {

  final case class DataStreamSettings(dataStreamName: DataStreamName.Full,
                                      documentMappings: Map[String, FieldType]) {
    def lifecyclePolicy: String = s"${dataStreamName.value.value}-lifecycle-policy"

    def componentTemplateSettingsId: TemplateName = templateNameFrom(s"${dataStreamName.value.value}-settings")

    def componentTemplateMappingsId: TemplateName = templateNameFrom(s"${dataStreamName.value.value}-mappings")

    def indexTemplate: TemplateName = templateNameFrom(s"${dataStreamName.value.value}-template")

    private def templateNameFrom(value: String) = {
      TemplateName
        .fromString(value)
        .getOrElse(throw new IllegalStateException("Template name should be non-empty"))
    }

    val mappings: Value = serializeMappings(documentMappings)

    val mappingsMetadata: Map[String, String] = {
      Map(
        "description" -> "Data mappings for ReadonlyREST audit data stream"
      )
    }

    val indexSettingsMetadata: Map[String, String] = {
      Map(
        "description" -> "Index settings for ReadonlyREST audit data stream"
      )
    }

    val indexTemplateMetadata: Map[String, String] = {
      Map(
        "description" -> "Index template for ReadonlyREST audit data stream"
      )
    }

    def lifecyclePolicyJson(maxPrimaryShardSizeInRollover: Boolean): Value = {
      ujson.Obj(
        "phases" -> ujson.Obj(
          "hot" -> ujson.Obj(
            "actions" -> ujson.Obj(
              "rollover" -> ujson.Obj.from(
                List[(String, Value)]("max_age" -> "1d") ++
                Option.when[(String, Value)](maxPrimaryShardSizeInRollover)("max_primary_shard_size" -> "50gb").toList
              )
            )
          ),
          "warm" -> ujson.Obj(
            "min_age" -> "14d",
            "actions" ->ujson.Obj(
              "shrink" -> ujson.Obj(
                "number_of_shards" -> 1
              ),
              "forcemerge" -> ujson.Obj(
                "max_num_segments" -> 1
              )
            )
          ),
          "cold" -> ujson.Obj(
            "min_age"-> "30d",
            "actions" -> ujson.Obj(
              "freeze" -> ujson.Obj()
            )
          )
        )
      )
    }

    private def serializeMappings(mappings: Map[String, FieldType]): ujson.Value = {
      val properties = mappings
        .view
        .mapValues {
          case FieldType.Str =>
            ujson.read(
              """
                |{
                |  "type": "text"
                |}
                |""".stripMargin
            )
          case FieldType.Long =>
            ujson.read(
              """
                |{
                |  "type": "long"
                |}
                |""".stripMargin
            )
          case FieldType.Bool =>
            ujson.read(
              """
                |{
                |  "type": "boolean"
                |}
                |""".stripMargin
            )
          case FieldType.Date =>
            ujson.read(
              """
                |{
                |  "type": "date",
                |  "format": "date_optional_time||epoch_millis"
                |}
                |""".stripMargin
            )
        }
      ujson.Obj("properties" -> ujson.Obj.from(properties))
    }


  }
}
