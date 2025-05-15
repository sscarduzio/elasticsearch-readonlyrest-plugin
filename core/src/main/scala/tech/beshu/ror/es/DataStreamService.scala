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

import cats.data.{EitherT, NonEmptyList}
import eu.timepit.refined.types.numeric.PosInt
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import tech.beshu.ror.accesscontrol.domain.{DataStreamName, TemplateName}
import tech.beshu.ror.es.DataStreamService.CreationResult.*
import tech.beshu.ror.es.DataStreamService.DataStreamSettings.*
import tech.beshu.ror.es.DataStreamService.{CreationResult, DataStreamSettings, DataStreamSetupResult}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.DurationOps.PositiveFiniteDuration
import tech.beshu.ror.utils.ScalaOps.retryBackoffEither

import scala.concurrent.duration.*

trait DataStreamService {

  final def fullySetupDataStream(settings: DataStreamSettings): Task[DataStreamSetupResult] = {
    for {
      _ <- createIfAbsent(
        checkIfResourceExists = checkIndexLifecyclePolicyExists(settings.lifecyclePolicy.id),
        createResource = createIndexLifecyclePolicy(settings.lifecyclePolicy),
        onNotAcknowledged = DataStreamSetupResult.Failure(s"Unable to determine if the index lifecycle policy with ID '${settings.lifecyclePolicy.id.show}' has been created")
      )
      _ <- createIfAbsent(
        checkIfResourceExists = checkComponentTemplateExists(settings.mappings.templateName),
        createResource = createComponentTemplateForMappings(settings.mappings),
        onNotAcknowledged = DataStreamSetupResult.Failure(s"Unable to determine if component template with ID '${settings.mappings.templateName.show}' has been created")
      )
      _ <- createIfAbsent(
        checkIfResourceExists = checkComponentTemplateExists(settings.componentSettings.templateName),
        createResource = createComponentTemplateForIndex(settings.componentSettings),
        onNotAcknowledged = DataStreamSetupResult.Failure(s"Unable to determine if component template with ID '${settings.componentSettings.templateName.show}' has been created")
      )
      _ <- createIfAbsent(
        checkIfResourceExists = checkIndexTemplateExists(settings.templateSettings.templateName),
        createResource = createIndexTemplate(settings.templateSettings),
        onNotAcknowledged = DataStreamSetupResult.Failure(s"Unable to determine if index template with ID '${settings.templateSettings.templateName.show}' has been created")
      )
      _ <- createIfAbsent(
        checkIfResourceExists = checkDataStreamExists(settings.dataStreamName),
        createResource = createDataStream(settings.dataStreamName),
        onNotAcknowledged = DataStreamSetupResult.Failure(s"Unable to determine if data stream with ID '${settings.dataStreamName.show}' has been created")
      )
    } yield DataStreamSetupResult.Success
  }.merge

  def checkDataStreamExists(dataStreamName: DataStreamName.Full): Task[Boolean]

  protected def createDataStream(dataStreamName: DataStreamName.Full): Task[CreationResult]

  protected def checkIndexLifecyclePolicyExists(policyId: NonEmptyString): Task[Boolean] = ??? // default implementation to be removed after porting changes to all modules

  protected def createIndexLifecyclePolicy(policy: LifecyclePolicy): Task[CreationResult]

  protected def checkComponentTemplateExists(templateName: TemplateName): Task[Boolean] = ??? // default implementation to be removed after porting changes to all modules

  protected def createComponentTemplateForMappings(settings: ComponentTemplateMappings): Task[CreationResult]

  protected def createComponentTemplateForIndex(settings: ComponentTemplateSettings): Task[CreationResult]

  protected def checkIndexTemplateExists(templateName: TemplateName): Task[Boolean] = ??? // default implementation to be removed after porting changes to all modules

  protected def createIndexTemplate(settings: IndexTemplateSettings): Task[CreationResult]

  private def createIfAbsent(checkIfResourceExists: Task[Boolean],
                             createResource: Task[CreationResult],
                             onNotAcknowledged: => DataStreamSetupResult.Failure): EitherT[Task, DataStreamSetupResult.Failure, Unit] = EitherT {
    checkIfResourceExists
      .flatMap {
        case true =>
          Task.pure(Acknowledged)
        case false =>
          createResourceWithConfirmation(checkIfResourceExists, createResource)
      }
      .map {
        case CreationResult.Acknowledged => Right(())
        case CreationResult.NotAcknowledged => Left(onNotAcknowledged)
      }
  }

  private def createResourceWithConfirmation(checkIfResourceExists: Task[Boolean],
                                             createResource: Task[CreationResult]): Task[CreationResult] = {
    createResource
      .flatMap {
        case Acknowledged =>
          Task.pure(Acknowledged)
        case NotAcknowledged =>
          withRetries(
            checkIfResourceExists.map(exists => Either.cond(exists, Acknowledged, NotAcknowledged))
          ).map(_.merge)
      }
  }

  private def withRetries[E, A](source: => Task[Either[E, A]]) =
    retryBackoffEither(
      source = source,
      maxRetries = retryConfig.maxRetries,
      firstDelay = retryConfig.initialDelay,
      backOffScaler = retryConfig.backoffScaler
    )

  protected val retryConfig: RetryConfig = RetryConfig(initialDelay = 500.milliseconds, backoffScaler = 2, maxRetries = 5)

  protected case class RetryConfig(initialDelay: FiniteDuration, backoffScaler: Int, maxRetries: Int)
}

object DataStreamService {

  sealed trait DataStreamSetupResult

  object DataStreamSetupResult {
    case object Success extends DataStreamSetupResult

    final case class Failure(reason: String) extends DataStreamSetupResult
  }

  final case class DataStreamSettings(dataStreamName: DataStreamName.Full,
                                      lifecyclePolicy: LifecyclePolicy,
                                      mappings: ComponentTemplateMappings,
                                      componentSettings: ComponentTemplateSettings,
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

    final case class ComponentTemplateMappings(templateName: TemplateName,
                                               timestampField: String,
                                               metadata: Map[String, String])

    final case class ComponentTemplateSettings(templateName: TemplateName,
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
