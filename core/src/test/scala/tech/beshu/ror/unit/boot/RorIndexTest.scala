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
package tech.beshu.ror.unit.boot

import java.time.Clock
import java.util.UUID

import eu.timepit.refined.auto._
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers.{a, be, _}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{EitherValues, Inside, OptionValues}
import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.AccessControl
import tech.beshu.ror.accesscontrol.AccessControl.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.accesscontrol.factory.{Core, CoreFactory}
import tech.beshu.ror.boot.RorInstance.TestConfigUpdated
import tech.beshu.ror.boot.{ReadonlyRest, RorInstance}
import tech.beshu.ror.configuration.{RawRorConfig, RorConfig}
import tech.beshu.ror.es.{AuditSinkService, IndexJsonContentService}
import tech.beshu.ror.providers.{EnvVarsProvider, OsEnvVarsProvider, PropertiesProvider}
import tech.beshu.ror.utils.DurationOps._
import tech.beshu.ror.utils.TestsPropertiesProvider
import tech.beshu.ror.utils.TestsUtils._

import scala.concurrent.duration._
import scala.language.{implicitConversions, postfixOps}

class RorIndexTest extends AnyWordSpec
  with Inside with OptionValues with EitherValues
  with MockFactory with Eventually {

  implicit private val envVarsProvider: EnvVarsProvider = OsEnvVarsProvider
  private implicit val testClock: Clock = Clock.systemUTC()

  private val defaultRorIndexName: NonEmptyString = ".readonlyrest"
  private val customRorIndexName: NonEmptyString = "custom_ror_index"

  private val mainRorConfigDocumentId = "1"
  private val testRorConfigDocumentId = "2"

  "A ReadonlyREST core" should {
    "load ROR index name" when {
      "no index is defined in config" should {
        "start ROR with default index name" in {
          val resourcesPath = "/boot_tests/index_config/no_index_defined/"
          val indexJsonContentService = mock[IndexJsonContentService]
          mockMainRorConfigFromIndexLoading(indexJsonContentService, defaultRorIndexName)
          mockTestRorConfigFromIndexLoading(indexJsonContentService, defaultRorIndexName)

          val coreFactory = mock[CoreFactory]
          mockCoreFactory(coreFactory, indexRorConfig)

          val result = readonlyRestBoot(
            coreFactory,
            indexJsonContentService,
            resourcesPath
          )
            .start()
            .runSyncUnsafe()

          result shouldBe a[Right[_, RorInstance]]
        }
        "save ROR config in default index" in {
          val resourcesPath = "/boot_tests/index_config/no_index_defined/"
          val indexJsonContentService = mock[IndexJsonContentService]
          mockMainRorConfigFromIndexLoading(indexJsonContentService, defaultRorIndexName)
          mockTestRorConfigFromIndexLoading(indexJsonContentService, defaultRorIndexName)

          val coreFactory = mock[CoreFactory]
          mockCoreFactory(coreFactory, indexRorConfig)

          val result = readonlyRestBoot(
            coreFactory,
            indexJsonContentService,
            resourcesPath
          )
            .start()
            .runSyncUnsafe()

          result shouldBe a[Right[_, RorInstance]]

          mockCoreFactory(coreFactory, rorConfig)
          mockMainRorConfigInIndexSaving(indexJsonContentService, defaultRorIndexName)

          val rorInstance = result.right.value
          val forceReloadingResult =
            rorInstance
              .forceReloadAndSave(rorConfig)(newRequestId())
              .runSyncUnsafe()

          forceReloadingResult should be(Right(()))
        }
        "save ROR test config in default index" in {
          val resourcesPath = "/boot_tests/index_config/no_index_defined/"
          val indexJsonContentService = mock[IndexJsonContentService]
          mockMainRorConfigFromIndexLoading(indexJsonContentService, defaultRorIndexName)
          mockTestRorConfigFromIndexLoading(indexJsonContentService, defaultRorIndexName)

          val coreFactory = mock[CoreFactory]
          mockCoreFactory(coreFactory, indexRorConfig)

          val result = readonlyRestBoot(
            coreFactory,
            indexJsonContentService,
            resourcesPath
          )
            .start()
            .runSyncUnsafe()

          result shouldBe a[Right[_, RorInstance]]

          mockCoreFactory(coreFactory, rorConfig)
          mockTestRorConfigInIndexSaving(indexJsonContentService, defaultRorIndexName)

          val rorInstance = result.right.value
          val forceReloadingResult =
            rorInstance
              .forceReloadTestConfigEngine(rorConfig, (5 minutes).toRefinedPositiveUnsafe)(newRequestId())
              .runSyncUnsafe()

          forceReloadingResult.right.value shouldBe a[TestConfigUpdated]
        }
      }
      "custom index is defined in config" should {
        "start ROR with custom index name" in {
          val resourcesPath = "/boot_tests/index_config/custom_index_defined/"
          val indexJsonContentService = mock[IndexJsonContentService]
          mockMainRorConfigFromIndexLoading(indexJsonContentService, customRorIndexName)
          mockTestRorConfigFromIndexLoading(indexJsonContentService, customRorIndexName)

          val coreFactory = mock[CoreFactory]
          mockCoreFactory(coreFactory, indexRorConfig)

          val result = readonlyRestBoot(
            coreFactory,
            indexJsonContentService,
            resourcesPath
          )
            .start()
            .runSyncUnsafe()
          result shouldBe a[Right[_, RorInstance]]
        }
        "save ROR config in custom index" in {
          val resourcesPath = "/boot_tests/index_config/custom_index_defined/"
          val indexJsonContentService = mock[IndexJsonContentService]
          mockMainRorConfigFromIndexLoading(indexJsonContentService, customRorIndexName)
          mockTestRorConfigFromIndexLoading(indexJsonContentService, customRorIndexName)

          val coreFactory = mock[CoreFactory]
          mockCoreFactory(coreFactory, indexRorConfig)

          val result = readonlyRestBoot(
            coreFactory,
            indexJsonContentService,
            resourcesPath
          )
            .start()
            .runSyncUnsafe()

          mockCoreFactory(coreFactory, rorConfig)
          mockMainRorConfigInIndexSaving(indexJsonContentService, customRorIndexName)

          val rorInstance = result.right.value
          val forceReloadingResult =
            rorInstance
              .forceReloadAndSave(rorConfig)(newRequestId())
              .runSyncUnsafe()

          forceReloadingResult should be(Right(()))
        }
        "save ROR test config in custom index" in {
          val resourcesPath = "/boot_tests/index_config/custom_index_defined/"
          val indexJsonContentService = mock[IndexJsonContentService]
          mockMainRorConfigFromIndexLoading(indexJsonContentService, customRorIndexName)
          mockTestRorConfigFromIndexLoading(indexJsonContentService, customRorIndexName)

          val coreFactory = mock[CoreFactory]
          mockCoreFactory(coreFactory, indexRorConfig)

          val result = readonlyRestBoot(
            coreFactory,
            indexJsonContentService,
            resourcesPath
          )
            .start()
            .runSyncUnsafe()

          mockCoreFactory(coreFactory, rorConfig)
          mockTestRorConfigInIndexSaving(indexJsonContentService, customRorIndexName)

          val rorInstance = result.right.value
          val forceReloadingResult =
            rorInstance
              .forceReloadTestConfigEngine(rorConfig, (5 minutes).toRefinedPositiveUnsafe)(newRequestId())
              .runSyncUnsafe()

          forceReloadingResult.right.value shouldBe a[TestConfigUpdated]
        }
      }
    }
  }

  private def readonlyRestBoot(factory: CoreFactory,
                               indexJsonContentService: IndexJsonContentService,
                               configPath: String,
                               refreshInterval: Option[FiniteDuration] = None) = {
    implicit def propertiesProvider: PropertiesProvider =
      TestsPropertiesProvider.usingMap(
        mapWithIntervalFrom(refreshInterval) ++
          Map(
            "com.readonlyrest.settings.loading.delay" -> "0"
          )
      )

    def mapWithIntervalFrom(refreshInterval: Option[FiniteDuration]) =
      refreshInterval
        .map(i => "com.readonlyrest.settings.refresh.interval" -> i.toSeconds.toString)
        .toMap

    ReadonlyRest.create(
      factory,
      indexJsonContentService,
      _ => mock[AuditSinkService],
      getResourcePath(configPath)
    )
  }

  private def mockCoreFactory(mockedCoreFactory: CoreFactory,
                              rawRorConfig: RawRorConfig): CoreFactory = {
    (mockedCoreFactory.createCoreFrom _)
      .expects(where {
        (config: RawRorConfig, _, _, _, _) => config == rawRorConfig
      })
      .once()
      .returns(Task.now(Right(Core(mockAccessControl, RorConfig.disabled))))
    mockedCoreFactory
  }

  private def mockMainRorConfigFromIndexLoading(indexJsonContentService: IndexJsonContentService,
                                                indexName: NonEmptyString) = {
    (indexJsonContentService.sourceOf _)
      .expects(fullIndexName(indexName), mainRorConfigDocumentId)
      .once()
      .returns(Task.now(Right(Map("settings" -> indexRorConfig.raw))))
  }

  private def mockTestRorConfigFromIndexLoading(indexJsonContentService: IndexJsonContentService,
                                                indexName: NonEmptyString) = {
    (indexJsonContentService.sourceOf _)
      .expects(fullIndexName(indexName), testRorConfigDocumentId)
      .once()
      .returns(Task.now(Left(IndexJsonContentService.ContentNotFound)))
  }

  private def mockMainRorConfigInIndexSaving(indexJsonContentService: IndexJsonContentService,
                                             indexName: NonEmptyString) = {
    (indexJsonContentService.saveContent _)
      .expects(fullIndexName(indexName), mainRorConfigDocumentId, Map("settings" -> rorConfig.raw))
      .once()
      .returns(Task.now(Right(())))
  }

  private def mockTestRorConfigInIndexSaving(indexJsonContentService: IndexJsonContentService,
                                             indexName: NonEmptyString) = {
    (indexJsonContentService.saveContent _)
      .expects(
        where {
          (config: IndexName.Full, id: String, content: Map[String, String]) =>
            config == fullIndexName(indexName) &&
              id == testRorConfigDocumentId &&
              content.get("settings").contains(rorConfig.raw) &&
              content.get("expiration_ttl_millis").contains("300000") &&
              content.contains("expiration_timestamp") &&
              content.contains("auth_services_mocks")
        }
      )
      .once()
      .returns(Task.now(Right(())))
  }

  private def mockAccessControl = {
    val mockedAccessControl = mock[AccessControl]
    (mockedAccessControl.staticContext _)
      .expects()
      .anyNumberOfTimes()
      .returns(mockAccessControlStaticContext)
    (mockedAccessControl.description _)
      .expects()
      .anyNumberOfTimes()
      .returns("ENABLED")
    mockedAccessControl
  }

  private def mockAccessControlStaticContext = {
    val mockedContext = mock[AccessControlStaticContext]
    (mockedContext.obfuscatedHeaders _)
      .expects()
      .anyNumberOfTimes()
      .returns(Set.empty)

    (mockedContext.usedFlsEngineInFieldsRule _)
      .expects()
      .anyNumberOfTimes()
      .returns(None)
    mockedContext
  }

  private def newRequestId() = RequestId(UUID.randomUUID().toString)

  private lazy val rorConfig = rorConfigFromUnsafe(
    """
      |readonlyrest:
      |  access_control_rules:
      |
      |  - name: test_block
      |    type: allow
      |    auth_key: admin:container
      |
      |  - name: test_block_2
      |    type: allow
      |    auth_key: dev:container
      |
      |""".stripMargin
  )

  private lazy val indexRorConfig = rorConfigFromUnsafe(
    """
      |readonlyrest:
      |  access_control_rules:
      |  - name: test_block
      |    type: allow
      |    auth_key: admin:container
      |""".stripMargin
  )

}
