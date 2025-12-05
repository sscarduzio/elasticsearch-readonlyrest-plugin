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

import better.files.File
import cats.implicits.toShow
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Json
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.apache.commons.text.StringEscapeUtils.escapeJava
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{EitherValues, Inside, OptionValues}
import tech.beshu.ror.SystemContext
import tech.beshu.ror.accesscontrol.AccessControlList
import tech.beshu.ror.accesscontrol.AccessControlList.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.audit.sink.AuditSinkServiceCreator
import tech.beshu.ror.accesscontrol.domain.{IndexName, RequestId, RorSettingsFile}
import tech.beshu.ror.accesscontrol.factory.{Core, CoreFactory, RorDependencies}
import tech.beshu.ror.boot.ReadonlyRest
import tech.beshu.ror.boot.RorInstance.TestSettings
import tech.beshu.ror.es.{EsEnv, IndexDocumentManager}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.settings.es.EsConfigBasedRorSettings
import tech.beshu.ror.settings.ror.RawRorSettings
import tech.beshu.ror.syntax.*
import tech.beshu.ror.unit.utils.WithReadonlyrestBootSupport
import tech.beshu.ror.utils.DurationOps.*
import tech.beshu.ror.utils.TestsUtils.*

import java.util.UUID
import scala.concurrent.duration.*
import scala.language.postfixOps

class IndexSettingsRelatedRorCoreTest extends AnyWordSpec
  with WithReadonlyrestBootSupport
  with Inside with OptionValues with EitherValues
  with MockFactory with Eventually {

  private val defaultRorIndexName: NonEmptyString = ".readonlyrest"
  private val customRorIndexName: NonEmptyString = "custom_ror_index"

  private val mainInIndexRorSettingsDocumentId = "1"
  private val testInIndexRorSettingsDocumentId = "2"

  "A ReadonlyREST core" should {
    "load ROR index name" when {
      "no index is defined in settings" should {
        "start ROR with default index name" in withReadonlyRest {
          val indexDocumentManager = mock[IndexDocumentManager]
          mockInIndexMainSettingsLoading(indexDocumentManager, defaultRorIndexName)
          mockInIndexTestSettingsLoading(indexDocumentManager, defaultRorIndexName)

          val coreFactory = mock[CoreFactory]
          mockCoreFactory(coreFactory, indexRorSettings)

          (
            createReadonlyRestBoot(coreFactory, indexDocumentManager),
            createEsConfigBasedRorSettings("/boot_tests/index_config/no_index_defined/")
          )
        } { _ =>
          // nothing - just should start
        }
        "save ROR settings in default index" in withReadonlyRestExt {
          val indexDocumentManager = mock[IndexDocumentManager]
          mockInIndexMainSettingsLoading(indexDocumentManager, defaultRorIndexName)
          mockInIndexTestSettingsLoading(indexDocumentManager, defaultRorIndexName)

          val coreFactory = mock[CoreFactory]
          mockCoreFactory(coreFactory, indexRorSettings)

          (
            createReadonlyRestBoot(coreFactory, indexDocumentManager),
            createEsConfigBasedRorSettings("/boot_tests/index_config/no_index_defined/"),
            (indexDocumentManager, coreFactory)
          )
        } { case (rorInstance, (indexDocumentManager, coreFactory)) =>

          mockCoreFactory(coreFactory, updatedRorSettings)
          mockInIndexMainSettingsSaving(indexDocumentManager, defaultRorIndexName, updatedRorSettings)

          val forceReloadingResult =
            rorInstance
              .forceReloadAndSave(updatedRorSettings)(newRequestId())
              .runSyncUnsafe()

          forceReloadingResult should be(Right(()))
        }
        "save ROR test settings in default index" in withReadonlyRestExt {
          val indexDocumentManager = mock[IndexDocumentManager]
          mockInIndexMainSettingsLoading(indexDocumentManager, defaultRorIndexName)
          mockInIndexTestSettingsLoading(indexDocumentManager, defaultRorIndexName)

          val coreFactory = mock[CoreFactory]
          mockCoreFactory(coreFactory, indexRorSettings)

          (
            createReadonlyRestBoot(coreFactory, indexDocumentManager),
            createEsConfigBasedRorSettings("/boot_tests/index_config/no_index_defined/"),
            (indexDocumentManager, coreFactory)
          )
        } { case (rorInstance, (indexDocumentManager, coreFactory)) =>

          mockCoreFactory(coreFactory, updatedRorSettings)
          mockInIndexTestSettingsSaving(indexDocumentManager, defaultRorIndexName, updatedRorSettings)

          val forceReloadingResult =
            rorInstance
              .forceReloadTestSettingsEngine(updatedRorSettings, (5 minutes).toRefinedPositiveUnsafe)(newRequestId())
              .runSyncUnsafe()

          forceReloadingResult.value shouldBe a[TestSettings.Present]
        }
      }
      "custom index is defined in settings" should {
        "start ROR with custom index name" in withReadonlyRest {
          val indexDocumentManager = mock[IndexDocumentManager]
          mockInIndexMainSettingsLoading(indexDocumentManager, customRorIndexName)
          mockInIndexTestSettingsLoading(indexDocumentManager, customRorIndexName)

          val coreFactory = mock[CoreFactory]
          mockCoreFactory(coreFactory, indexRorSettings)

          (
            createReadonlyRestBoot(coreFactory, indexDocumentManager),
            createEsConfigBasedRorSettings("/boot_tests/index_config/custom_index_defined/")
          )
        } { _ =>
          // nothing - just should start
        }
        "save ROR settings in custom index" in withReadonlyRestExt {
          val indexDocumentManager = mock[IndexDocumentManager]
          mockInIndexMainSettingsLoading(indexDocumentManager, customRorIndexName)
          mockInIndexTestSettingsLoading(indexDocumentManager, customRorIndexName)

          val coreFactory = mock[CoreFactory]
          mockCoreFactory(coreFactory, indexRorSettings)

          (
            createReadonlyRestBoot(coreFactory, indexDocumentManager),
            createEsConfigBasedRorSettings("/boot_tests/index_config/custom_index_defined/"),
            (indexDocumentManager, coreFactory)
          )
        } { case (rorInstance, (indexDocumentManager, coreFactory)) =>

          mockCoreFactory(coreFactory, updatedRorSettings)
          mockInIndexMainSettingsSaving(indexDocumentManager, customRorIndexName, updatedRorSettings)

          val forceReloadingResult =
            rorInstance
              .forceReloadAndSave(updatedRorSettings)(newRequestId())
              .runSyncUnsafe()

          forceReloadingResult should be(Right(()))
        }
        "save ROR test settings in custom index" in withReadonlyRestExt {
          val indexDocumentManager = mock[IndexDocumentManager]
          mockInIndexMainSettingsLoading(indexDocumentManager, customRorIndexName)
          mockInIndexTestSettingsLoading(indexDocumentManager, customRorIndexName)

          val coreFactory = mock[CoreFactory]
          mockCoreFactory(coreFactory, indexRorSettings)

          (
            createReadonlyRestBoot(coreFactory, indexDocumentManager),
            createEsConfigBasedRorSettings("/boot_tests/index_config/custom_index_defined/"),
            (indexDocumentManager, coreFactory)
          )
        } { case (rorInstance, (indexDocumentManager, coreFactory)) =>

          mockCoreFactory(coreFactory, updatedRorSettings)
          mockInIndexTestSettingsSaving(indexDocumentManager, customRorIndexName, updatedRorSettings)

          val forceReloadingResult =
            rorInstance
              .forceReloadTestSettingsEngine(updatedRorSettings, (5 minutes).toRefinedPositiveUnsafe)(newRequestId())
              .runSyncUnsafe()

          forceReloadingResult.value shouldBe a[TestSettings.Present]
        }
      }
    }
  }

  private def createReadonlyRestBoot(factory: CoreFactory,
                                     indexDocumentManager: IndexDocumentManager) = {
    implicit val systemContext: SystemContext = SystemContext.default
    ReadonlyRest.create(factory, indexDocumentManager, mock[AuditSinkServiceCreator])
  }

  private def mockCoreFactory(mockedCoreFactory: CoreFactory,
                              rawRorSettings: RawRorSettings): CoreFactory = {
    (mockedCoreFactory.createCoreFrom _)
      .expects(where {
        (settings: RawRorSettings, _, _, _, _) => settings == rawRorSettings
      })
      .once()
      .returns(Task.now(Right(Core(mockAccessControl, RorDependencies.noOp, None))))
    mockedCoreFactory
  }

  private def mockInIndexMainSettingsLoading(indexDocumentManager: IndexDocumentManager,
                                             indexName: NonEmptyString) = {
    (indexDocumentManager.documentAsJson _)
      .expects(fullIndexName(indexName), mainInIndexRorSettingsDocumentId)
      .once()
      .returns(Task.now(Right(circeJsonFrom(s"""{ "settings": "${escapeJava(indexRorSettings.rawYaml)}" }"""))))
  }

  private def mockInIndexTestSettingsLoading(indexDocumentManager: IndexDocumentManager,
                                             indexName: NonEmptyString) = {
    (indexDocumentManager.documentAsJson _)
      .expects(fullIndexName(indexName), testInIndexRorSettingsDocumentId)
      .once()
      .returns(Task.now(Left(IndexDocumentManager.DocumentNotFound)))
  }

  private def mockInIndexMainSettingsSaving(indexDocumentManager: IndexDocumentManager,
                                            indexName: NonEmptyString,
                                            rawRorSettings: RawRorSettings) = {
    (indexDocumentManager.saveDocumentJson _)
      .expects(fullIndexName(indexName), mainInIndexRorSettingsDocumentId, circeJsonFrom(s"""{ "settings": "${escapeJava(rawRorSettings.rawYaml)}"}"""))
      .once()
      .returns(Task.now(Right(())))
  }

  private def mockInIndexTestSettingsSaving(indexDocumentManager: IndexDocumentManager,
                                            indexName: NonEmptyString,
                                            rawRorSettings: RawRorSettings) = {
    (indexDocumentManager.saveDocumentJson _)
      .expects(
        where {
          (index: IndexName.Full, id: String, document: Json) =>
            index == fullIndexName(indexName) &&
              id == testInIndexRorSettingsDocumentId &&
              document.hcursor.get[String]("settings").toOption.contains(rawRorSettings.rawYaml) &&
              document.hcursor.get[String]("expiration_ttl_millis").toOption.contains("300000") &&
              document.hcursor.downField("expiration_timestamp").succeeded &&
              document.hcursor.downField("auth_services_mocks").succeeded
        }
      )
      .once()
      .returns(Task.now(Right(())))
  }

  private def mockAccessControl = {
    val mockedAccessControl = mock[AccessControlList]
    (() => mockedAccessControl.staticContext)
      .expects()
      .anyNumberOfTimes()
      .returns(mockAccessControlStaticContext)
    (() => mockedAccessControl.description)
      .expects()
      .anyNumberOfTimes()
      .returns("ENABLED")
    mockedAccessControl
  }

  private def mockAccessControlStaticContext = {
    val mockedContext = mock[AccessControlStaticContext]
    (() => mockedContext.obfuscatedHeaders)
      .expects()
      .anyNumberOfTimes()
      .returns(Set.empty)

    (() => mockedContext.usedFlsEngineInFieldsRule)
      .expects()
      .anyNumberOfTimes()
      .returns(None)
    mockedContext
  }

  private def newRequestId() = RequestId(UUID.randomUUID().toString)

  private def createEsConfigBasedRorSettings(resourceEsConfigDir: String): EsConfigBasedRorSettings = {
    implicit val systemContext: SystemContext = SystemContext.default
    val esConfig = File(getResourcePath(resourceEsConfigDir))
    val esEnv = EsEnv(esConfig, esConfig, defaultEsVersionForTests, testEsNodeSettings)
    EsConfigBasedRorSettings.from(esEnv).runSyncUnsafe() match {
      case Right(settings) =>
        val rorSettingsSourcesConfig = settings.settingsSource.copy(settingsFile = RorSettingsFile(esConfig / "readonlyrest.yml"))
        settings.copy(settingsSource = rorSettingsSourcesConfig)
      case Left(error) =>
        throw new IllegalStateException(s"Cannot create EsConfigBasedRorSettings: ${error.show}")
    }
  }

  private lazy val indexRorSettings = rorSettingsFromUnsafe(
    """
      |readonlyrest:
      |  access_control_rules:
      |  - name: test_block
      |    type: allow
      |    auth_key: admin:container
      |""".stripMargin
  )

  private lazy val updatedRorSettings = rorSettingsFromUnsafe(
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

}
