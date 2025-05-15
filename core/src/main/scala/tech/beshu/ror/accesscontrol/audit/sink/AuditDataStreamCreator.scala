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

import cats.data.Validated.Valid
import cats.data.{NonEmptyList, Validated}
import cats.implicits.*
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.audit.sink.AuditDataStreamCreator.ErrorMessage
import tech.beshu.ror.accesscontrol.domain.{DataStreamName, RorAuditDataStream, TemplateName}
import tech.beshu.ror.es.DataStreamService
import tech.beshu.ror.es.DataStreamService.DataStreamSettings.*
import tech.beshu.ror.es.DataStreamService.{DataStreamSettings, DataStreamSetupResult}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.RefinedUtils.*

import java.util.concurrent.TimeUnit

final class AuditDataStreamCreator(services: NonEmptyList[DataStreamService]) extends Logging {

  def createIfNotExists(dataStreamName: RorAuditDataStream): Task[Either[NonEmptyList[ErrorMessage], Unit]] = {
    services
      .toList
      .map(createIfNotExists(_, dataStreamName))
      .sequence
      .map(_.map(_.leftMap(NonEmptyList.one)).combineAll.toEither)
  }

  private def createIfNotExists(service: DataStreamService, dataStreamName: RorAuditDataStream): Task[Validated[ErrorMessage, Unit]] = {
    service
      .checkDataStreamExists(dataStreamName.dataStream)
      .flatMap {
        case true =>
          Task.delay(logger.info(s"Data stream ${dataStreamName.dataStream.show} already exists"))
            .as(Valid(()))
        case false =>
          val settings = defaultSettingsFor(dataStreamName.dataStream)
          setupDataStream(service, settings)
      }
  }

  private def setupDataStream(service: DataStreamService, settings: DataStreamSettings): Task[Validated[ErrorMessage, Unit]] = {
    for {
      _ <- Task.delay(logger.info(s"Trying to setup ROR audit data stream ${settings.dataStreamName.show} with default settings.."))
      result <- service.fullySetupDataStream(settings).attempt
      finalResult <- result match {
        case Right(DataStreamSetupResult.Success) =>
          Task.delay(logger.info(s"ROR audit data stream ${settings.dataStreamName.show} created."))
            .as(Valid(()))
        case Right(DataStreamSetupResult.Failure(reason)) =>
          val message = s"Failed to setup ROR audit data stream ${settings.dataStreamName.show}. Reason: ${reason.show}"
          Task.delay(logger.error(message))
            .as(ErrorMessage(message).invalid)
        case Left(ex) =>
          val message = s"An unexpected error occurred while setting up the ROR audit data stream ${settings.dataStreamName.show}. Details: ${ex.getMessage}"
          Task.delay(logger.error(message, ex))
            .as(ErrorMessage(message).invalid)
      }
    } yield finalResult
  }

  private def defaultSettingsFor(dataStreamName: DataStreamName.Full) = {
    val defaultLifecyclePolicy = LifecyclePolicy(
      id = NonEmptyString.unsafeFrom(s"${dataStreamName.value.value}-lifecycle-policy"),
      hotPhase = LifecyclePolicy.HotPhase(
        LifecyclePolicy.Rollover(
          maxAge = positiveFiniteDuration(1, TimeUnit.DAYS),
          maxPrimaryShardSizeInGb = Some(positiveInt(50))
        )
      ),
      warmPhase = Some(LifecyclePolicy.WarmPhase(
        minAge = positiveFiniteDuration(14, TimeUnit.DAYS),
        shrink = Some(LifecyclePolicy.Shrink(numberOfShards = positiveInt(1))),
        forceMerge = Some(LifecyclePolicy.ForceMerge(maxNumSegments = positiveInt(1)))
      )),
      coldPhase = Some(LifecyclePolicy.ColdPhase(
        minAge = positiveFiniteDuration(30, TimeUnit.DAYS),
        freeze = true
      ))
    )

    val defaultMappings = ComponentTemplateMappings(
      templateName = templateNameFrom(s"${dataStreamName.value.value}-mappings"),
      timestampField = "@timestamp",
      metadata = metadata("Data mappings for ReadonlyREST audit data stream")
    )

    val settings = ComponentTemplateSettings(
      templateName = templateNameFrom(s"${dataStreamName.value.value}-settings"),
      lifecyclePolicyId = defaultLifecyclePolicy.id,
      metadata = metadata("Index settings for ReadonlyREST audit data stream")
    )

    val indexTemplate = IndexTemplateSettings(
      templateName = templateNameFrom(s"${dataStreamName.value.value}-template"),
      dataStreamName = dataStreamName,
      componentTemplates = NonEmptyList.of(defaultMappings.templateName, settings.templateName),
      metadata = metadata("Index template for ReadonlyREST audit data stream")
    )

    DataStreamSettings(
      dataStreamName,
      defaultLifecyclePolicy,
      defaultMappings,
      settings,
      indexTemplate,
    )
  }

  private def templateNameFrom(value: String) = {
    TemplateName
      .fromString(value)
      .getOrElse(throw new IllegalStateException("Template name should be non-empty"))
  }

  private def metadata(description: String) = Map("description" -> description)

}

object AuditDataStreamCreator {
  final case class ErrorMessage(message: String)
}
