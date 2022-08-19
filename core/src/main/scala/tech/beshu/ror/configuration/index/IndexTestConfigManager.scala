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
package tech.beshu.ror.configuration.index

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}
import java.util.concurrent.TimeUnit

import cats.data.EitherT
import cats.implicits._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.domain.RorConfigurationIndex
import tech.beshu.ror.configuration.TestRorConfig.Present
import tech.beshu.ror.configuration.index.IndexConfigError.{IndexConfigNotExist, IndexConfigUnknownStructure}
import tech.beshu.ror.configuration.index.IndexTestConfigManager.Const
import tech.beshu.ror.configuration.loader.ConfigLoader.ConfigLoaderError
import tech.beshu.ror.configuration.loader.ConfigLoader.ConfigLoaderError.{ParsingError, SpecializedError}
import tech.beshu.ror.configuration.{RawRorConfig, TestRorConfig}
import tech.beshu.ror.es.IndexJsonContentService
import tech.beshu.ror.es.IndexJsonContentService.{CannotReachContentSource, CannotWriteToIndex, ContentNotFound}
import tech.beshu.ror.utils.DurationOps._

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.Try

final class IndexTestConfigManager(indexJsonContentService: IndexJsonContentService)
  extends BaseIndexConfigManager[TestRorConfig]
  with Logging {

  override def load(indexName: RorConfigurationIndex): Task[Either[ConfigLoaderError[IndexConfigError], TestRorConfig]] = {
    indexJsonContentService
      .sourceOf(indexName.index, Config.rorTestSettingsIndexConst.id)
      .flatMap {
        case Right(source) =>
          val properties = source.collect { case (key: String, value: String) => (key, value) }
          getSettings(properties).value
        case Left(CannotReachContentSource) =>
          configLoaderError(IndexConfigNotExist)
        case Left(ContentNotFound) =>
          Task.now(Right(TestRorConfig.NotSet))
      }
  }

  override def save(config: TestRorConfig, rorConfigurationIndex: RorConfigurationIndex): Task[Either[SavingIndexConfigError, Unit]] = {
    indexJsonContentService
      .saveContent(
        rorConfigurationIndex.index,
        Config.rorTestSettingsIndexConst.id,
        formatSettings(config)
      )
      .map {
        _.left.map { case CannotWriteToIndex => SavingIndexConfigError.CannotSaveConfig }
      }
  }

  private def getSettings(config: Map[String, String]): EitherT[Task, ConfigLoaderError[IndexConfigError], TestRorConfig] = {

    if (config.isEmpty) {
      EitherT.right(Task.now(TestRorConfig.NotSet))
    } else {
      for {
        expirationTimeString <- getConfigProperty(config, Const.properties.expirationTime)
        expirationTtlString <- getConfigProperty(config, Const.properties.expirationTtl)
        rawRorConfigString <- getConfigProperty(config, Const.properties.settings)
        rawRorConfig <- EitherT(RawRorConfig.fromString(rawRorConfigString).map(_.left.map(ParsingError.apply)))
        expirationTime <- getInstant(expirationTimeString)
        expirationTtl <- getExpirationTtl(expirationTtlString)
      } yield Present(
        rawConfig = rawRorConfig,
        expiration = Present.ExpirationConfig(ttl = expirationTtl, validTo = expirationTime)
      )
    }
  }

  private def formatSettings(config: TestRorConfig): Map[String, String] = {
    config match {
      case TestRorConfig.NotSet =>
        Map.empty
      case Present(rawConfig, expiration) =>
        Map(
          Const.properties.expirationTime -> expiration.validTo.atOffset(ZoneOffset.UTC).toString,
          Const.properties.expirationTtl -> expiration.ttl.value.toMillis.toString,
          Const.properties.settings -> rawConfig.raw
        )
    }
  }

  private def getExpirationTtl(value: String): EitherT[Task, ConfigLoaderError[IndexConfigError], FiniteDuration Refined Positive] = {
    Try {
      Duration
        .apply(value.toLong, TimeUnit.MILLISECONDS)
        .toRefinedPositive
        .leftMap((_: String) => parserError)
    }
      .toEither
      .leftMap(_ => parserError)
      .flatten
      .toEitherT[Task]
  }

  private def parserError: ConfigLoaderError[IndexConfigError] = SpecializedError[IndexConfigError](IndexConfigUnknownStructure)

  private def getInstant(value: String): EitherT[Task, ConfigLoaderError[IndexConfigError], Instant] = {
    Try(DateTimeFormatter.ISO_DATE_TIME.parse(value))
      .map(Instant.from)
      .toEither
      .toEitherT[Task]
      .leftMap(_ => parserError)
  }

  private def getConfigProperty[A, B](map: Map[A, B], key: A): EitherT[Task, ConfigLoaderError[IndexConfigError], B] = {
    map
      .get(key)
      .toRight(parserError)
      .toEitherT[Task]
  }

}

private object IndexTestConfigManager {
  object Const {
    object properties {
      val settings = "settings"
      val expirationTtl = "expiration_ttl_millis"
      val expirationTime = "expiration_timestamp"
    }
  }
}