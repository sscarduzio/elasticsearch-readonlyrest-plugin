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
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.domain.{DataStreamName, RorAuditDataStream, TemplateName}
import tech.beshu.ror.es.DataStreamService
import tech.beshu.ror.es.DataStreamService.DataStreamSettings
import tech.beshu.ror.es.DataStreamService.DataStreamSettings.*
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.RefinedUtils.*

import java.util.concurrent.TimeUnit

final class DataStreamAuditSinkCreator(services: NonEmptyList[DataStreamService]) extends Logging {

  def createIfNotExists(dataStreamName: RorAuditDataStream): Task[Unit] = {
    services.toList.traverse(createIfNotExists(_, dataStreamName)).map((_: List[Unit]) => ())
  }

  private def createIfNotExists(service: DataStreamService, dataStreamName: RorAuditDataStream): Task[Unit] = {
    service
      .checkDataStreamExists(dataStreamName.dataStream)
      .flatMap {
        case true =>
          Task.delay(logger.info(s"Data stream ${dataStreamName.dataStream.show} already exists"))
        case false =>
          val settings = defaultSettingsFor(dataStreamName.dataStream)
          setupDataStream(service, settings)
      }
  }

  private def setupDataStream(service: DataStreamService, settings: DataStreamSettings): Task[Unit] = {
    for {
      _ <- Task.delay(logger.info(s"Trying to setup ROR audit sink with default settings for data stream ${settings.dataStreamName.show}.."))
      _ <- service.fullySetupDataStream(settings)
      _ <- Task.delay(logger.info(s"ROR audit data stream ${settings.dataStreamName.show} created."))
    } yield ()
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

