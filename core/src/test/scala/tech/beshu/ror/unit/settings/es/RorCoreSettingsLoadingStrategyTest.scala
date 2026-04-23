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
package tech.beshu.ror.unit.settings.es

import monix.execution.Scheduler.Implicits.global
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.SystemContext
import tech.beshu.ror.settings.es.ElasticsearchConfigLoader.LoadingError.MalformedSettings
import tech.beshu.ror.settings.es.RorCoreSettingsLoadingStrategy
import tech.beshu.ror.settings.es.RorCoreSettingsLoadingStrategy.*
import tech.beshu.ror.settings.es.RorCoreSettingsLoadingStrategy.CoreRefreshSettings.{Disabled, Enabled}
import tech.beshu.ror.settings.es.RorCoreSettingsLoadingStrategy.LoadingRetryStrategySettings.*
import tech.beshu.ror.utils.DurationOps.RefinedDurationOps
import tech.beshu.ror.utils.TestsPropertiesProvider
import tech.beshu.ror.utils.TestsUtils.withEsEnv

import scala.concurrent.duration.*
import scala.language.postfixOps

class RorCoreSettingsLoadingStrategyTest extends AnyWordSpec with Inside {

  "ROR core settings loading strategy" should {
    "default to loading from index with file fallback and default values" when {
      "no readonlyrest settings are present in elasticsearch config" in {
        val result = load(
          """
            |node.name: n1_it
            |cluster.initial_master_nodes: n1_it
            |""".stripMargin
        )

        result should be(Right(LoadFromIndexWithFileFallback(
          indexLoadingRetrySettings = defaultRetrySettings,
          coreRefreshSettings = Enabled((5 seconds).toRefinedPositiveUnsafe)
        )))
      }
      "force_load_from_file is set to false" in {
        val result = load(
          """
            |readonlyrest:
            |  force_load_from_file: false
            |""".stripMargin
        )

        result should be(Right(LoadFromIndexWithFileFallback(
          indexLoadingRetrySettings = defaultRetrySettings,
          coreRefreshSettings = Enabled((5 seconds).toRefinedPositiveUnsafe)
        )))
      }
    }
    "resolve to ForceLoadingFromFileSettings" when {
      "force_load_from_file is set to true" in {
        val result = load(
          """
            |readonlyrest:
            |  force_load_from_file: true
            |""".stripMargin
        )

        result should be(Right(ForceLoadingFromFileSettings))
      }
    }
    "load all retry and refresh settings from elasticsearch config" in {
      val result = load(
        """
          |readonlyrest:
          |  load_from_index:
          |    initial_loading_retry_strategy:
          |      attempts_interval: 10s
          |      attempts_count: 3
          |      initial_delay: 2s
          |    poll_interval: 30s
          |""".stripMargin
      )

      result should be(Right(LoadFromIndexWithFileFallback(
        indexLoadingRetrySettings = LoadingRetryStrategySettings(
          attemptsInterval = LoadingAttemptsInterval.unsafeFrom(10 seconds),
          attemptsCount = LoadingAttemptsCount.unsafeFrom(3),
          delay = LoadingDelay.unsafeFrom(2 seconds)
        ),
        coreRefreshSettings = Enabled((30 seconds).toRefinedPositiveUnsafe)
      )))
    }
    "disable core refresh" when {
      "poll_interval is set to 0" in {
        val result = load(
          """
            |readonlyrest:
            |  load_from_index:
            |    poll_interval: 0s
            |""".stripMargin
        )

        result should be(Right(LoadFromIndexWithFileFallback(
          indexLoadingRetrySettings = defaultRetrySettings,
          coreRefreshSettings = Disabled
        )))
      }
    }
    "read retry settings from legacy JVM system properties" when {
      "all legacy properties are set" in {
        val properties = Map(
          "com.readonlyrest.settings.refresh.interval"         -> "20s",
          "com.readonlyrest.settings.loading.delay"            -> "3s",
          "com.readonlyrest.settings.loading.attempts.interval" -> "7s",
          "com.readonlyrest.settings.loading.attempts.count"   -> "10"
        )
        val result = load(
          """
            |node.name: n1_it
            |cluster.initial_master_nodes: n1_it
            |""".stripMargin,
          properties
        )

        result should be(Right(LoadFromIndexWithFileFallback(
          indexLoadingRetrySettings = LoadingRetryStrategySettings(
            attemptsInterval = LoadingAttemptsInterval.unsafeFrom(7 seconds),
            attemptsCount = LoadingAttemptsCount.unsafeFrom(10),
            delay = LoadingDelay.unsafeFrom(3 seconds)
          ),
          coreRefreshSettings = Enabled((20 seconds).toRefinedPositiveUnsafe)
        )))
      }
      "legacy refresh interval is given as integer seconds" in {
        val properties = Map("com.readonlyrest.settings.refresh.interval" -> "15")
        val result = load(
          """
            |node.name: n1_it
            |cluster.initial_master_nodes: n1_it
            |""".stripMargin,
          properties
        )

        result should be(Right(LoadFromIndexWithFileFallback(
          indexLoadingRetrySettings = defaultRetrySettings,
          coreRefreshSettings = Enabled((15 seconds).toRefinedPositiveUnsafe)
        )))
      }
      "legacy refresh interval of 0 disables refresh" in {
        val properties = Map("com.readonlyrest.settings.refresh.interval" -> "0")
        val result = load(
          """
            |node.name: n1_it
            |cluster.initial_master_nodes: n1_it
            |""".stripMargin,
          properties
        )

        result should be(Right(LoadFromIndexWithFileFallback(
          indexLoadingRetrySettings = defaultRetrySettings,
          coreRefreshSettings = Disabled
        )))
      }
    }
    "fail to load" when {
      "attempts_interval has an invalid value" in {
        inside(load(
          """
            |readonlyrest:
            |  load_from_index:
            |    initial_loading_retry_strategy:
            |      attempts_interval: not-a-duration
            |""".stripMargin
        )) {
          case Left(MalformedSettings(_, message)) =>
            message should include(
              "Invalid value at '.readonlyrest.load_from_index.initial_loading_retry_strategy.attempts_interval': " +
                "Cannot parse 'not-a-duration' as a duration. Expected a finite duration like '5s', '1m'"
            )
        }
      }
      "attempts_count has an invalid value" in {
        inside(load(
          """
            |readonlyrest:
            |  load_from_index:
            |    initial_loading_retry_strategy:
            |      attempts_count: not-a-number
            |""".stripMargin
        )) {
          case Left(MalformedSettings(_, message)) =>
            message should include(
              "Invalid value at '.readonlyrest.load_from_index.initial_loading_retry_strategy.attempts_count': " +
                "Cannot convert 'not-a-number' to non-negative integer"
            )
        }
      }
      "poll_interval has an invalid value" in {
        inside(load(
          """
            |readonlyrest:
            |  load_from_index:
            |    poll_interval: not-a-duration
            |""".stripMargin
        )) {
          case Left(MalformedSettings(_, message)) =>
            message should include(
              "Invalid value at '.readonlyrest.load_from_index.poll_interval': " +
                "Cannot parse 'not-a-duration' as a duration. Expected a finite duration like '5s', '1m'"
            )
        }
      }
    }
  }

  private lazy val defaultRetrySettings = LoadingRetryStrategySettings(
    attemptsInterval = LoadingAttemptsInterval.unsafeFrom(5 seconds),
    attemptsCount = LoadingAttemptsCount.unsafeFrom(5),
    delay = LoadingDelay.unsafeFrom(5 seconds)
  )

  private def load(yaml: String,
                   properties: Map[String, String] = Map.empty) = {
    implicit val systemContext: SystemContext = new SystemContext(
      propertiesProvider = TestsPropertiesProvider.usingMap(properties)
    )
    withEsEnv(yaml) { (esEnv, _) =>
      RorCoreSettingsLoadingStrategy.load(esEnv).runSyncUnsafe()
    }
  }
}
