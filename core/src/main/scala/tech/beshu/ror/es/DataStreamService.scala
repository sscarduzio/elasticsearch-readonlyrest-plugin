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
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import tech.beshu.ror.accesscontrol.domain.{DataStreamName, TemplateName}
import tech.beshu.ror.es.DataStreamService.DataStreamSettings.*
import tech.beshu.ror.es.DataStreamService.{CreationResult, DataStreamSettings}
import tech.beshu.ror.utils.DurationOps.PositiveFiniteDuration

trait DataStreamService {

  final def fullySetupDataStream(settings: DataStreamSettings): Task[CreationResult] = {
    for {
      _ <- createIndexLifecyclePolicy(settings.lifecyclePolicy)
      _ <- createComponentTemplateForMappings(settings.mappings)
      _ <- createComponentTemplateForIndex(settings.componentSettings)
      _ <- createIndexTemplate(settings.templateSettings)
      result <- createDataStream(settings.dataStreamName)
    } yield result
  }

  def checkDataStreamExists(dataStreamName: DataStreamName.Full): Task[Boolean]

  protected def createDataStream(dataStreamName: DataStreamName.Full): Task[CreationResult]

  protected def createIndexLifecyclePolicy(policy: LifecyclePolicy): Task[CreationResult]

  protected def createComponentTemplateForMappings(settings: ComponentMappings): Task[CreationResult]

  protected def createComponentTemplateForIndex(settings: ComponentSettings): Task[CreationResult]

  protected def createIndexTemplate(settings: IndexTemplateSettings): Task[CreationResult]
}

object DataStreamService {

  final case class DataStreamSettings(dataStreamName: DataStreamName.Full,
                                      lifecyclePolicy: LifecyclePolicy,
                                      mappings: ComponentMappings,
                                      componentSettings: ComponentSettings,
                                      templateSettings: IndexTemplateSettings)

  object DataStreamSettings {
    final case class LifecyclePolicy(id: NonEmptyString,
                                     hotPhase: LifecyclePolicy.HotPhase,
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

    final case class ComponentMappings(templateName: TemplateName,
                                       timestampField: String,
                                       metadata: Map[String, String])

    final case class ComponentSettings(templateName: TemplateName,
                                       lifecyclePolicyId: NonEmptyString,
                                       metadata: Map[String, String])

    final case class IndexTemplateSettings(templateName: TemplateName,
                                           dataStreamName: DataStreamName.Full,
                                           componentTemplates: NonEmptyList[TemplateName],
                                           metadata: Map[String, String])

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
