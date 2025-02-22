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
package tech.beshu.ror.es

import cats.data.NonEmptyList
import eu.timepit.refined.types.numeric.PosInt
import monix.eval.Task
import tech.beshu.ror.accesscontrol.domain.{DataStreamName, TemplateName}
import tech.beshu.ror.audit.instances.FieldType
import tech.beshu.ror.es.DataStreamService.DataStreamSettings.LifecyclePolicy
import tech.beshu.ror.es.DataStreamService.{CreationResult, DataStreamSettings}
import tech.beshu.ror.utils.DurationOps.PositiveFiniteDuration
import ujson.Value


trait DataStreamService {

  final def fullySetupDataStream(settings: DataStreamSettings): Task[CreationResult] = {
    for {
      _ <- createIndexLifecyclePolicy(settings.lifecyclePolicyId, settings.lifecyclePolicy)
      _ <- createComponentTemplateForMappings(settings.componentTemplateMappingsId, settings.mappings, settings.mappingsMetadata)
      _ <- createComponentTemplateForIndex(settings.componentTemplateSettingsId, settings.lifecyclePolicyId, settings.indexSettingsMetadata)
      _ <- createIndexTemplate(settings.indexTemplate, settings.dataStreamName, NonEmptyList.of(settings.componentTemplateMappingsId, settings.componentTemplateSettingsId), settings.indexTemplateMetadata)
      result <- createDataStream(settings.dataStreamName)
    } yield result
  }

  def checkDataStreamExists(dataStreamName: DataStreamName.Full): Task[Boolean]

  def createDataStream(dataStreamName: DataStreamName.Full): Task[CreationResult]

  protected def createIndexLifecyclePolicy(policyName: String, policy: DataStreamSettings.LifecyclePolicy): Task[CreationResult]

  protected def createComponentTemplateForMappings(templateName: TemplateName,
                                                   mappingsJson: ujson.Value,
                                                   metadata: Map[String, String]): Task[CreationResult]

  protected def createComponentTemplateForIndex(templateName: TemplateName,
                                                lifecyclePolicyName: String,
                                                metadata: Map[String, String]): Task[CreationResult]

  protected def createIndexTemplate(templateName: TemplateName,
                                    dataStreamName: DataStreamName.Full,
                                    componentTemplates: NonEmptyList[TemplateName],
                                    metadata: Map[String, String]): Task[CreationResult]
}

object DataStreamService {

  final case class DataStreamSettings(dataStreamName: DataStreamName.Full,
                                      lifecyclePolicy: LifecyclePolicy,
                                      documentMappings: Map[String, FieldType]) {
    def lifecyclePolicyId: String = s"${dataStreamName.value.value}-lifecycle-policy"

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

  object DataStreamSettings {
    final case class LifecyclePolicy(hotPhase: LifecyclePolicy.HotPhase,
                                     warmPhase: Option[LifecyclePolicy.WarmPhase],
                                     coldPhase: Option[LifecyclePolicy.ColdPhase])

    object LifecyclePolicy {
      final case class Rollover(maxAge: PositiveFiniteDuration, maxPrimaryShardSizeInGb: Option[PosInt] = None)

      final case class Shrink(numberOfShards: PosInt)

      final case class ForceMerge(maxNumSegments: PosInt)

      final case class HotPhase(rollover: Rollover)

      final case class WarmPhase(minAge: PositiveFiniteDuration, shrink: Option[Shrink], forceMerge: Option[ForceMerge])

      final case class ColdPhase(minAge: PositiveFiniteDuration, freeze: Boolean)

    }
  }

  sealed trait CreationResult
  object CreationResult {
    case object Acknowledged extends CreationResult
    case object NotAcknowledged extends CreationResult

    def apply(acknowledged: Boolean): CreationResult = if (acknowledged) {
      Acknowledged
    } else {
      NotAcknowledged
    }
  }
}
