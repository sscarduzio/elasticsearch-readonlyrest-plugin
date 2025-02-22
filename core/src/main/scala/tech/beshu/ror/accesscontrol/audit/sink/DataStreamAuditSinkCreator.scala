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
import tech.beshu.ror.accesscontrol.domain.DataStreamName
import tech.beshu.ror.audit.instances.FieldType
import tech.beshu.ror.es.DataStreamService
import tech.beshu.ror.es.DataStreamService.DataStreamSettings
import tech.beshu.ror.es.DataStreamService.DataStreamSettings.LifecyclePolicy
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.RefinedUtils.*

import java.util.concurrent.TimeUnit

final class DataStreamAuditSinkCreator(services: NonEmptyList[DataStreamService]) extends Logging {

  def createIfNotExists(dataStreamName: DataStreamName.Full,
                        documentMappings: Map[String, FieldType]): Task[Unit] = {
    services.toList.traverse(createIfNotExists(_, dataStreamName, documentMappings)).map((_: List[Unit]) => ())
  }

  private def createIfNotExists(service: DataStreamService, dataStreamName: DataStreamName.Full, documentMappings: Map[String, FieldType]): Task[Unit] = {
    service
      .checkDataStreamExists(dataStreamName)
      .flatMap {
        case true =>
          Task.delay(logger.info(s"Data stream ${dataStreamName.show} already exists"))
        case false =>
          val settings = DataStreamSettings(
            dataStreamName,
            defaultLifecyclePolicy,
            documentMappings
          )
          setupDataStream(service, settings)
      }
  }

  private def setupDataStream(service: DataStreamService, settings: DataStreamSettings): Task[Unit] = {
    for {
      _ <- Task.delay(logger.info(s"Trying to setup ROR audit sink with data stream ${settings.dataStreamName.show}.."))
      _ <- service.fullySetupDataStream(settings)
      _ <- Task.delay(logger.info(s"ROR audit data stream ${settings.dataStreamName.show} created."))
    } yield ()
  }

  private val defaultLifecyclePolicy = LifecyclePolicy(
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
}

