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
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.Inside
import tech.beshu.ror.SystemContext
import tech.beshu.ror.settings.es.RorBootSettings
import tech.beshu.ror.settings.es.RorBootSettings.{RorFailedToStartResponse, RorNotStartedResponse}
import tech.beshu.ror.settings.es.ElasticsearchConfigLoader.LoadingError.MalformedSettings
import tech.beshu.ror.utils.TestsPropertiesProvider
import tech.beshu.ror.utils.TestsUtils.withEsEnv

class RorBootSettingsTest
  extends AnyWordSpec with Inside {

  "A ReadonlyREST ES starting settings" should {
    "be loaded from elasticsearch config file" when {
      "boot settings contains not started response code" in {
        val settings = load(
          """
            |readonlyrest:
            |  not_started_response_code: 503
            |""".stripMargin
        )

        settings should be(Right(RorBootSettings(
          rorNotStartedResponse = RorNotStartedResponse(RorNotStartedResponse.HttpCode.`503`),
          rorFailedToStartResponse = RorFailedToStartResponse(RorFailedToStartResponse.HttpCode.`403`),
        )))
      }
      "boot settings contains failed to start response code" in {
        val settings = load(
          """
            |readonlyrest:
            |  failed_to_start_response_code: 503
            |""".stripMargin
        )

        settings should be(Right(RorBootSettings(
          rorNotStartedResponse = RorNotStartedResponse(RorNotStartedResponse.HttpCode.`403`),
          rorFailedToStartResponse = RorFailedToStartResponse(RorFailedToStartResponse.HttpCode.`503`),
        )))
      }
      "boot settings contains all codes" in {
        val settings = load(
          """
            |readonlyrest:
            |  failed_to_start_response_code: 503
            |  not_started_response_code: 403
            |""".stripMargin
        )

        settings should be(Right(RorBootSettings(
          rorNotStartedResponse = RorNotStartedResponse(RorNotStartedResponse.HttpCode.`403`),
          rorFailedToStartResponse = RorFailedToStartResponse(RorFailedToStartResponse.HttpCode.`503`),
        )))
      }
    }
    "there is no response codes defined in config, default values should be used" in {
      val settings = load(
        """
          |node.name: n1_it
          |cluster.initial_master_nodes: n1_it
          |""".stripMargin
      )

      settings should be(Right(RorBootSettings(
        rorNotStartedResponse = RorNotStartedResponse(RorNotStartedResponse.HttpCode.`403`),
        rorFailedToStartResponse = RorFailedToStartResponse(RorFailedToStartResponse.HttpCode.`403`),
      )))
    }
    "be loaded from JVM properties" when {
      "not_started_response_code is set via property" in {
        val settings = load(
          """
            |node.name: n1_it
            |""".stripMargin,
          properties = Map("readonlyrest.not_started_response_code" -> "503")
        )

        settings should be(Right(RorBootSettings(
          rorNotStartedResponse = RorNotStartedResponse(RorNotStartedResponse.HttpCode.`503`),
          rorFailedToStartResponse = RorFailedToStartResponse(RorFailedToStartResponse.HttpCode.`403`),
        )))
      }
      "failed_to_start_response_code is set via property" in {
        val settings = load(
          """
            |node.name: n1_it
            |""".stripMargin,
          properties = Map("readonlyrest.failed_to_start_response_code" -> "503")
        )

        settings should be(Right(RorBootSettings(
          rorNotStartedResponse = RorNotStartedResponse(RorNotStartedResponse.HttpCode.`403`),
          rorFailedToStartResponse = RorFailedToStartResponse(RorFailedToStartResponse.HttpCode.`503`),
        )))
      }
      "all settings are set via properties" in {
        val settings = load(
          """
            |node.name: n1_it
            |""".stripMargin,
          properties = Map(
            "readonlyrest.not_started_response_code" -> "503",
            "readonlyrest.failed_to_start_response_code" -> "503"
          )
        )

        settings should be(Right(RorBootSettings(
          rorNotStartedResponse = RorNotStartedResponse(RorNotStartedResponse.HttpCode.`503`),
          rorFailedToStartResponse = RorFailedToStartResponse(RorFailedToStartResponse.HttpCode.`503`),
        )))
      }
    }
  }
  "not be able to load" when {
    "not started response code is malformed" in {
      inside(load(
        """
          |readonlyrest:
          |  not_started_response_code: 200
          |""".stripMargin
      )) {
        case Left(MalformedSettings(_, message)) =>
          message should include(
            "Invalid value at '.readonlyrest.not_started_response_code': Unsupported HTTP code '200'. Allowed values: '403', '503'"
          )
      }
    }
    "failed to start response code is malformed" in {
      inside(load(
        """
          |readonlyrest:
          |  failed_to_start_response_code: 200
          |""".stripMargin
      )) {
        case Left(MalformedSettings(_, message)) =>
          message should include(
            "Invalid value at '.readonlyrest.failed_to_start_response_code': Unsupported HTTP code '200'. Allowed values: '403', '503'"
          )
      }
    }
  }

  private def load(yaml: String, properties: Map[String, String] = Map.empty) = {
    implicit val systemContext: SystemContext = new SystemContext(
      propertiesProvider = TestsPropertiesProvider.usingMap(properties)
    )
    withEsEnv(yaml) { (esEnv, _) =>
      RorBootSettings.load(esEnv).runSyncUnsafe()
    }
  }
}
