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
package tech.beshu.ror.unit.configuration

import monix.execution.Scheduler.Implicits.global
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.configuration.RorBootConfiguration.{RorFailedToStartResponse, RorNotStartedResponse}
import tech.beshu.ror.configuration.{EnvironmentConfig, MalformedSettings, RorBootConfiguration}
import tech.beshu.ror.es.EsEnv
import tech.beshu.ror.utils.TestsUtils.getResourcePath

class RorBootConfigurationTest
  extends AnyWordSpec with Inside {

  private implicit val environmentConfig: EnvironmentConfig = EnvironmentConfig.default

  "A ReadonlyREST ES starting settings" should {
    "be loaded from elasticsearch config file" when {
      "configuration contains not started response code" in {
        val config = RorBootConfiguration
          .load(esEnvFrom("/boot_tests/boot_config/not_started_code_defined/"))
          .runSyncUnsafe()

        config.map(_.rorNotStartedResponse) should be(Right(
          RorNotStartedResponse(RorNotStartedResponse.HttpCode.`503`)
        ))
      }
      "configuration contains failed to start response code" in {
        val config = RorBootConfiguration
          .load(esEnvFrom("/boot_tests/boot_config/failed_to_start_code_defined/"))
          .runSyncUnsafe()

        config.map(_.rorFailedToStartResponse) should be(Right(
          RorFailedToStartResponse(RorFailedToStartResponse.HttpCode.`503`)
        ))
      }
      "configuration contains all codes" in {
        val config = RorBootConfiguration
          .load(esEnvFrom("/boot_tests/boot_config/all_codes_defined/"))
          .runSyncUnsafe()

        config should be(Right(RorBootConfiguration(
          rorNotStartedResponse = RorNotStartedResponse(RorNotStartedResponse.HttpCode.`403`),
          rorFailedToStartResponse = RorFailedToStartResponse(RorFailedToStartResponse.HttpCode.`503`),
        )))
      }
    }
    "there is no response codes defined in config, default values should be used" in {
      val config = RorBootConfiguration
        .load(esEnvFrom("/boot_tests/boot_config/"))
        .runSyncUnsafe()

      config should be(Right(RorBootConfiguration(
        rorNotStartedResponse = RorNotStartedResponse(RorNotStartedResponse.HttpCode.`403`),
        rorFailedToStartResponse = RorFailedToStartResponse(RorFailedToStartResponse.HttpCode.`403`),
      )))
    }
  }
  "not be able to load" when {
    "not started response code is malformed" in {
      val configFolderPath = "/boot_tests/boot_config/not_started_code_malformed/"
      val expectedFilePath = getResourcePath(s"${configFolderPath}elasticsearch.yml").toString

      RorBootConfiguration.load(esEnvFrom(configFolderPath)).runSyncUnsafe() shouldBe Left {
        MalformedSettings(
          s"Cannot load ROR boot configuration from file $expectedFilePath. " +
          s"Cause: Unsupported response code [200] for readonlyrest.not_started_response_code. Supported response codes are: 403, 503."
        )
      }
    }
    "failed to start response code is malformed" in {
      val configFolderPath = "/boot_tests/boot_config/failed_to_start_code_malformed/"
      val expectedFilePath = getResourcePath(s"${configFolderPath}elasticsearch.yml").toString

      RorBootConfiguration.load(esEnvFrom(configFolderPath)).runSyncUnsafe() shouldBe Left {
        MalformedSettings(
          s"Cannot load ROR boot configuration from file $expectedFilePath. " +
          s"Cause: Unsupported response code [200] for readonlyrest.failed_to_start_response_code. Supported response codes are: 403, 503."
        )
      }
    }
  }

  private def esEnvFrom(configFolderPath: String) = {
    EsEnv(getResourcePath(configFolderPath), getResourcePath(configFolderPath))
  }
}
