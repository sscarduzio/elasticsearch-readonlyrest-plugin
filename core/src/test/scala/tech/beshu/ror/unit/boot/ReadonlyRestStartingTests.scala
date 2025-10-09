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
import cats.data.NonEmptyList
import cats.implicits.*
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Json
import io.lemonlabs.uri.Uri
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.apache.commons.text.StringEscapeUtils.escapeJava
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{EitherValues, Inside, OptionValues}
import squants.information.Bytes
import tech.beshu.ror.SystemContext
import tech.beshu.ror.accesscontrol.AccessControlList
import tech.beshu.ror.accesscontrol.AccessControlList.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.audit.AuditingTool
import tech.beshu.ror.accesscontrol.audit.AuditingTool.AuditSettings.AuditSink
import tech.beshu.ror.accesscontrol.audit.sink.{AuditDataStreamCreator, AuditSinkServiceCreator, DataStreamAndIndexBasedAuditSinkServiceCreator}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService
import tech.beshu.ror.accesscontrol.blocks.definitions.{ExternalAuthenticationService, ExternalAuthorizationService}
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider.{ExternalAuthenticationServiceMock, ExternalAuthorizationServiceMock, LdapServiceMock}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RorDependencies.NoOpImpersonationWarningsReader
import tech.beshu.ror.accesscontrol.factory.{Core, CoreFactory, RorDependencies}
import tech.beshu.ror.accesscontrol.logging.AccessControlListLoggingDecorator
import tech.beshu.ror.boot.ReadonlyRest.StartingFailure
import tech.beshu.ror.boot.RorInstance.{IndexSettingsInvalidationError, TestSettings}
import tech.beshu.ror.boot.{ReadonlyRest, RorInstance}
import tech.beshu.ror.settings.es.EsConfigBasedRorSettings.LoadingError
import tech.beshu.ror.settings.es.EsConfigBasedRorSettings
import tech.beshu.ror.es.DataStreamService.CreationResult.{Acknowledged, NotAcknowledged}
import tech.beshu.ror.es.DataStreamService.{CreationResult, DataStreamSettings}
import tech.beshu.ror.es.IndexDocumentManager.*
import tech.beshu.ror.es.{DataStreamBasedAuditSinkService, DataStreamService, EsEnv, IndexDocumentManager}
import tech.beshu.ror.settings.ror.RawRorSettings
import tech.beshu.ror.settings.ror.source.IndexSettingsSource.SavingError.CannotSaveSettings
import tech.beshu.ror.settings.ror.source.ReadWriteSettingsSource.SavingSettingsError
import tech.beshu.ror.syntax.*
import tech.beshu.ror.unit.utils.WithReadonlyrestBootSupport
import tech.beshu.ror.utils.DurationOps.*
import tech.beshu.ror.utils.TestsPropertiesProvider
import tech.beshu.ror.utils.TestsUtils.*

import java.time.Clock
import java.util.UUID
import scala.concurrent.duration.*
import scala.language.postfixOps
import tech.beshu.ror.utils.misc.ScalaUtils.StringOps

class ReadonlyRestStartingTests
  extends AnyWordSpec
    with WithReadonlyrestBootSupport
    with Inside with OptionValues with EitherValues
    with MockFactory with Eventually {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(15, Seconds)), interval = scaled(Span(100, Millis)))

  private implicit val testClock: Clock = Clock.systemUTC()

  "A ReadonlyREST core" should {
    "support the main engine" should {
      "be loaded from file" when {
        "index is not available but file settings is provided" in withReadonlyRest({
          val resourcePath = "/boot_tests/no_index_config_file_config_provided"
          val mockedIndexDocumentManager = mock[IndexDocumentManager]
          mockGettingMainSettingsReturnsError(mockedIndexDocumentManager, error = IndexNotFound)
          mockGettingTestSettingsReturnsError(mockedIndexDocumentManager, error = IndexNotFound)
          val coreFactory = mockCoreFactory(mock[CoreFactory], s"$resourcePath/readonlyrest.yml")

          implicit val systemContext: SystemContext = createSystemContext()
          (
            readonlyRestBoot(coreFactory, mockedIndexDocumentManager),
            forceCreateEsConfigBasedRorSettings(resourcePath)
          )
        }) { rorInstance =>
          val acl = rorInstance.engines.value.mainEngine.core.accessControl
          acl shouldBe a[AccessControlListLoggingDecorator]
          acl.asInstanceOf[AccessControlListLoggingDecorator].underlying shouldBe a[EnabledAcl]
        }
        "file loading is forced in elasticsearch.yml" in withReadonlyRest({
          val resourcePath = "/boot_tests/forced_file_loading/"
          val coreFactory = mockCoreFactory(mock[CoreFactory], s"$resourcePath/readonlyrest.yml")
          implicit val systemContext: SystemContext = createSystemContext()
          (
            readonlyRestBoot(coreFactory, mock[IndexDocumentManager]),
            forceCreateEsConfigBasedRorSettings(resourcePath)
          )
        }) { rorInstance =>
          val acl = rorInstance.engines.value.mainEngine.core.accessControl
          acl shouldBe a[AccessControlListLoggingDecorator]
          acl.asInstanceOf[AccessControlListLoggingDecorator].underlying shouldBe a[EnabledAcl]
        }
      }
      "be loaded from index" when {
        "index is available and file settings is provided" in withReadonlyRest({
          val resourcesPath = "/boot_tests/index_config_available_file_config_provided/"
          val indexSettingsFile = "readonlyrest_index.yml"
          val mockedIndexDocumentManager = mock[IndexDocumentManager]
          mockGettingMainSettings(mockedIndexDocumentManager, resourcesPath + indexSettingsFile)
          mockGettingTestSettingsReturnsError(mockedIndexDocumentManager, error = DocumentNotFound)
          val coreFactory = mockCoreFactory(mock[CoreFactory], resourcesPath + indexSettingsFile)
          implicit val systemContext: SystemContext = createSystemContext()
          (
            readonlyRestBoot(coreFactory, mockedIndexDocumentManager),
            forceCreateEsConfigBasedRorSettings(resourcesPath)
          )
        }) { rorInstance =>
          val acl = rorInstance.engines.value.mainEngine.core.accessControl
          acl shouldBe a[AccessControlListLoggingDecorator]
          acl.asInstanceOf[AccessControlListLoggingDecorator].underlying shouldBe a[EnabledAcl]
        }
        "index is available and file settings is not provided" in withReadonlyRest({
          val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
          val indexSettingsFile = "readonlyrest_index.yml"
          val mockedIndexDocumentManager = mock[IndexDocumentManager]
          mockGettingMainSettings(mockedIndexDocumentManager, resourcesPath + indexSettingsFile)
          mockGettingTestSettingsReturnsError(mockedIndexDocumentManager, error = DocumentNotFound)
          val coreFactory = mockCoreFactory(mock[CoreFactory], resourcesPath + indexSettingsFile)
          implicit val systemContext: SystemContext = createSystemContext()
          (
            readonlyRestBoot(coreFactory, mockedIndexDocumentManager),
            forceCreateEsConfigBasedRorSettings(resourcesPath)
          )
        }) { rorInstance =>
          val acl = rorInstance.engines.value.mainEngine.core.accessControl
          acl shouldBe a[AccessControlListLoggingDecorator]
          acl.asInstanceOf[AccessControlListLoggingDecorator].underlying shouldBe a[EnabledAcl]
        }
      }
      "be able to be reloaded" when {
        "new config is different than old one" in withReadonlyRest({
          val resourcesPath = "/boot_tests/config_reloading/"
          val initialIndexSettingsFile = "readonlyrest_initial.yml"
          val newIndexSettingsFile = "readonlyrest_first.yml"

          val mockedIndexDocumentManager = mock[IndexDocumentManager]
          mockGettingMainSettings(mockedIndexDocumentManager, resourcesPath + initialIndexSettingsFile)
          mockSavingMainSettings(mockedIndexDocumentManager, resourcesPath + newIndexSettingsFile)
          mockGettingTestSettingsReturnsError(mockedIndexDocumentManager, error = DocumentNotFound)

          val coreFactory = mock[CoreFactory]
          mockCoreFactory(coreFactory, resourcesPath + initialIndexSettingsFile)
          mockCoreFactory(coreFactory, resourcesPath + newIndexSettingsFile)

          implicit val systemContext: SystemContext = createSystemContext(refreshInterval = Some(0 seconds))
          (
            readonlyRestBoot(coreFactory, mockedIndexDocumentManager),
            forceCreateEsConfigBasedRorSettings(resourcesPath)
          )
        }) { rorInstance =>
          val mainEngine = rorInstance.engines.value.mainEngine
          mainEngine.core.accessControl shouldBe a[AccessControlListLoggingDecorator]
          mainEngine.core.accessControl.asInstanceOf[AccessControlListLoggingDecorator].underlying shouldBe a[EnabledAcl]

          val reload1Result = rorInstance
            .forceReloadAndSave(rorSettingsFromResource("/boot_tests/config_reloading/readonlyrest_first.yml"))(newRequestId())
            .runSyncUnsafe()

          reload1Result should be(Right(()))
          assert(mainEngine != rorInstance.engines.value.mainEngine, "Engine was not reloaded")
        }
        "two parallel force reloads are invoked" in withReadonlyRestExt({
          val resourcesPath = "/boot_tests/config_reloading/"
          val initialIndexSettingsFile = "readonlyrest_initial.yml"
          val firstNewIndexSettingsFile = "readonlyrest_first.yml"
          val secondNewIndexSettingsFile = "readonlyrest_second.yml"

          val mockedIndexDocumentManager = mock[IndexDocumentManager]
          mockGettingMainSettings(mockedIndexDocumentManager, resourcesPath + initialIndexSettingsFile)
          mockGettingTestSettingsReturnsError(mockedIndexDocumentManager, error = DocumentNotFound)

          val coreFactory = mock[CoreFactory]
          mockCoreFactory(coreFactory, resourcesPath + initialIndexSettingsFile)
          mockCoreFactory(coreFactory, resourcesPath + firstNewIndexSettingsFile)
          mockCoreFactory(coreFactory, resourcesPath + secondNewIndexSettingsFile,
            createCoreResult =
              Task
                .sleep(100 millis)
                .map(_ => Right(Core(mockEnabledAccessControl, RorDependencies.noOp, None))) // very long creation
          )
          mockSavingMainSettings(
            mockedIndexDocumentManager,
            resourcesPath + firstNewIndexSettingsFile,
            Task.sleep(500 millis).map(_ => Right(())) // very long saving
          )

          implicit val systemContext: SystemContext = createSystemContext(refreshInterval = Some(0 seconds))

          (
            readonlyRestBoot(coreFactory, mockedIndexDocumentManager),
            forceCreateEsConfigBasedRorSettings(resourcesPath),
            mockedIndexDocumentManager
          )
        }) { case (rorInstance, mockedIndexDocumentManager) =>
          val resourcesPath = "/boot_tests/config_reloading/"
          val firstNewIndexSettingsFile = "readonlyrest_first.yml"
          val secondNewIndexSettingsFile = "readonlyrest_second.yml"

          eventually {
            rorInstance.engines.value.mainEngine.core.accessControl
          }

          val results = Task
            .parSequence(List(
              rorInstance
                .forceReloadAndSave(rorSettingsFromResource(resourcesPath + firstNewIndexSettingsFile))(newRequestId())
                .map { result =>
                  // schedule after first finish
                  mockSavingMainSettings(mockedIndexDocumentManager, resourcesPath + secondNewIndexSettingsFile)
                  result
                },
              Task
                .sleep(200 millis)
                .flatMap { _ =>
                  rorInstance.forceReloadAndSave(rorSettingsFromResource(resourcesPath + secondNewIndexSettingsFile))(newRequestId())
                }
            ))
            .runSyncUnsafe()
            .sequence

          results should be(Right(List((), ())))
        }
      }
      "be reloaded if index settings change" in withReadonlyRest({
        val resourcesPath = "/boot_tests/index_config_reloading/"
        val originIndexSettingsFile = "readonlyrest.yml"
        val updatedIndexSettingsFile = "updated_readonlyrest.yml"

        val mockedIndexDocumentManager = mock[IndexDocumentManager]
        val coreFactory = mock[CoreFactory]

        mockGettingMainSettings(mockedIndexDocumentManager, resourcesPath + originIndexSettingsFile)
        mockGettingTestSettingsReturnsError(mockedIndexDocumentManager, error = DocumentNotFound)
        mockCoreFactory(coreFactory, resourcesPath + originIndexSettingsFile, mockDisabledAccessControl)

        mockGettingMainSettings(mockedIndexDocumentManager, resourcesPath + updatedIndexSettingsFile, AttemptCount.AnyNumberOfTimes)
        mockGettingTestSettingsReturnsError(mockedIndexDocumentManager, error = DocumentNotFound, AttemptCount.AnyNumberOfTimes)
        mockCoreFactory(coreFactory, resourcesPath + updatedIndexSettingsFile)

        implicit val systemContext: SystemContext = createSystemContext(refreshInterval = Some(2 seconds))

        (
          readonlyRestBoot(coreFactory, mockedIndexDocumentManager),
          forceCreateEsConfigBasedRorSettings(resourcesPath)
        )
      }) { rorInstance =>
        val acl = rorInstance.engines.value.mainEngine.core.accessControl
        acl shouldBe a[AccessControlListLoggingDecorator]
        acl.asInstanceOf[AccessControlListLoggingDecorator].underlying shouldBe a[DisabledAcl]

        Task
          .sleep(4 seconds)
          .runSyncUnsafe()

        val acl2 = rorInstance.engines.value.mainEngine.core.accessControl
        acl2 shouldBe a[AccessControlListLoggingDecorator]
        acl2.asInstanceOf[AccessControlListLoggingDecorator].underlying shouldBe a[EnabledAcl]
      }
      "failed to load" when {
        "force load from file is set and settings file is malformed yaml" in {
          val resourcesPath = "/boot_tests/forced_file_loading_malformed_config/"
          implicit val systemContext: SystemContext = createSystemContext()
          val result = createEsConfigBasedRorSettings(resourcesPath)

          inside(result) { case Left(LoadingError.MalformedContent(_, message)) =>
            message should startWith("Cannot parse file")
          }
        }
        "force load from file is set and core cannot be loaded" in {
          val resourcesPath = "/boot_tests/forced_file_loading_bad_config/"
          val coreFactory = mockFailedCoreFactory(mock[CoreFactory], resourcesPath + "readonlyrest.yml")

          implicit val systemContext: SystemContext = createSystemContext()
          val readonlyRest = readonlyRestBoot(coreFactory, mock[IndexDocumentManager])
          val esConfigBasedRorSettings = forceCreateEsConfigBasedRorSettings(resourcesPath)

          val result = readonlyRest.start(esConfigBasedRorSettings).runSyncUnsafe()

          inside(result) { case Left(failure) =>
            failure.message shouldBe "Errors:\nfailed"
          }
        }
        "index settings don't exist and settings file is malformed yaml" in {
          val resourcesPath = "/boot_tests/index_config_not_exists_malformed_file_config/"
          val mockedIndexDocumentManager = mock[IndexDocumentManager]
          mockGettingMainSettingsReturnsError(mockedIndexDocumentManager, error = IndexNotFound)
          mockGettingTestSettingsReturnsError(mockedIndexDocumentManager, error = IndexNotFound)
          val coreFactory = mockFailedCoreFactory(mock[CoreFactory], resourcesPath + "readonlyrest.yml")

          implicit val systemContext: SystemContext = createSystemContext()

          val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexDocumentManager)
          val esConfigBasedRorSettings = forceCreateEsConfigBasedRorSettings(resourcesPath)

          val result = readonlyRest.start(esConfigBasedRorSettings).runSyncUnsafe()

          inside(result) { case Left(failure) =>
            failure.message shouldBe "Errors:\nfailed"
          }
        }
        "index settings don't exist and core cannot be loaded" in {
          val resourcePath = "/boot_tests/index_config_not_exists_bad_file_config/"
          val mockedIndexDocumentManager = mock[IndexDocumentManager]
          mockGettingMainSettingsReturnsError(mockedIndexDocumentManager, error = DocumentNotFound)
          mockGettingTestSettingsReturnsError(mockedIndexDocumentManager, error = DocumentNotFound)

          val coreFactory = mockFailedCoreFactory(mock[CoreFactory], resourcePath + "readonlyrest.yml")
          implicit val systemContext: SystemContext = createSystemContext()

          val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexDocumentManager)
          val esConfigBasedRorSettings = forceCreateEsConfigBasedRorSettings(resourcePath)

          val result = readonlyRest.start(esConfigBasedRorSettings).runSyncUnsafe()

          inside(result) { case Left(failure) =>
            failure.message shouldBe "Errors:\nfailed"
          }
        }
        "index settings are malformed" in {
          val resourcesPath = "/boot_tests/malformed_index_config/"
          val indexSettingsFile = "readonlyrest_index.yml"

          val mockedIndexDocumentManager = mock[IndexDocumentManager]
          mockGettingMainSettings(mockedIndexDocumentManager, resourcesPath + indexSettingsFile)
          mockGettingTestSettingsReturnsError(mockedIndexDocumentManager, error = DocumentNotFound)

          val coreFactory = mockFailedCoreFactory(mock[CoreFactory], resourcesPath + "readonlyrest.yml")

          implicit val systemContext: SystemContext = createSystemContext()

          val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexDocumentManager)
          val esConfigBasedRorSettings = forceCreateEsConfigBasedRorSettings(resourcesPath)

          val result = readonlyRest.start(esConfigBasedRorSettings).runSyncUnsafe()

          inside(result) { case Left(failure) =>
            failure.message shouldBe "Errors:\nfailed"
          }
        }
        "index settings cannot be loaded" in {
          val resourcesPath = "/boot_tests/bad_index_config/"
          val indexSettingsFile = "readonlyrest_index.yml"

          val mockedIndexDocumentManager = mock[IndexDocumentManager]
          mockGettingMainSettings(mockedIndexDocumentManager, resourcesPath + indexSettingsFile)
          mockGettingTestSettingsReturnsError(mockedIndexDocumentManager, error = DocumentNotFound)

          val coreFactory = mockFailedCoreFactory(mock[CoreFactory], resourcesPath + indexSettingsFile)
          implicit val systemContext: SystemContext = createSystemContext()
          val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexDocumentManager)
          val esConfigBasedRorSettings = forceCreateEsConfigBasedRorSettings(resourcesPath)

          val result = readonlyRest.start(esConfigBasedRorSettings).runSyncUnsafe()

          inside(result) { case Left(failure) =>
            failure.message shouldBe "Errors:\nfailed"
          }
        }
        "ROR SSL (in elasticsearch.yml) is tried to be used when XPack Security is enabled" in {
          implicit val systemContext: SystemContext = createSystemContext()
          val result = createEsConfigBasedRorSettings("/boot_tests/ror_ssl_declared_in_es_file_xpack_security_enabled/")

          inside(result) {
            case Left(LoadingError.MalformedContent(_, "Cannot use ROR SSL when XPack Security is enabled")) =>
          }
        }
        "ROR SSL (in readonlyrest.yml) is tried to be used when XPack Security is enabled" in {
          implicit val systemContext: SystemContext = createSystemContext()
          val result = createEsConfigBasedRorSettings("/boot_tests/ror_ssl_declared_in_readonlyrest_file_xpack_security_enabled/")

          inside(result) {
            case Left(LoadingError.MalformedContent(_, "Cannot use ROR SSL when XPack Security is enabled")) =>
          }
        }
        "ROR FIPS SSL is tried to be used when XPack Security is enabled" in {
          implicit val systemContext: SystemContext = createSystemContext()
          val result = createEsConfigBasedRorSettings("/boot_tests/ror_fisb_ssl_declared_in_readonlyrest_file_xpack_security_enabled/")

          inside(result) {
            case Left(LoadingError.MalformedContent(_, "Cannot use ROR SSL when XPack Security is enabled")) =>
          }
        }
      }
    }
    "support the test engine" which {
      "can be initialized" when {
        "there is no settings in index" in withReadonlyRest({
          val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
          val indexSettingsFile = "readonlyrest_index.yml"

          val mockedIndexDocumentManager = mock[IndexDocumentManager]
          mockGettingMainSettings(mockedIndexDocumentManager, resourcesPath + indexSettingsFile)
          mockGettingTestSettingsReturnsError(mockedIndexDocumentManager, error = DocumentNotFound)

          val coreFactory = mock[CoreFactory]
          mockCoreFactory(coreFactory, resourcesPath + indexSettingsFile)

          implicit val systemContext: SystemContext = createSystemContext(refreshInterval = Some(5 seconds))

          (
            readonlyRestBoot(coreFactory, mockedIndexDocumentManager),
            forceCreateEsConfigBasedRorSettings(resourcesPath)
          )
        }) { rorInstance =>
          rorInstance.engines.value.impersonatorsEngine should be(Option.empty)

          rorInstance.currentTestSettings()(newRequestId()).runSyncUnsafe() should be(TestSettings.NotSet)
        }
        "there is some settings stored in index" should {
          "load test engine as active" when {
            "settings are still valid" in withReadonlyRestExt({
              val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
              val indexSettingsFile = "readonlyrest_index.yml"
              val expirationTimestamp = testClock.instant().plusSeconds(100)

              val mockedIndexDocumentManager = mock[IndexDocumentManager]
              mockGettingMainSettings(mockedIndexDocumentManager, resourcesPath + indexSettingsFile)
              mockGettingTestSettings(
                mockedIndexDocumentManager,
                circeJsonFrom(
                  s"""{
                     |  "settings": "${escapeJava(testSettings1.rawYaml)}",
                     |  "expiration_ttl_millis": "100000",
                     |  "expiration_timestamp": "${expirationTimestamp.toString}",
                     |  "auth_services_mocks": $configuredAuthServicesMocksJson
                     |}""".stripMargin
                )
              )

              val coreFactory = mock[CoreFactory]
              mockCoreFactory(coreFactory, resourcesPath + indexSettingsFile)
              mockCoreFactory(coreFactory, testSettings1, mockEnabledAccessControl, RorDependencies.noOp, None)

              implicit val systemContext: SystemContext = createSystemContext(refreshInterval = Some(10 seconds))

              (
                readonlyRestBoot(coreFactory, mockedIndexDocumentManager),
                forceCreateEsConfigBasedRorSettings(resourcesPath),
                expirationTimestamp
              )
            }) { case (rorInstance, expirationTimestamp) =>
              rorInstance.engines.value.impersonatorsEngine.value.core.accessControl shouldBe a[AccessControlListLoggingDecorator]

              rorInstance.currentTestSettings()(newRequestId()).runSyncUnsafe() should be(
                TestSettings.Present(
                  dependencies = RorDependencies.noOp,
                  rawSettings = testSettings1,
                  configuredTtl = (100 seconds).toRefinedPositiveUnsafe,
                  validTo = expirationTimestamp
                )
              )

              rorInstance.mocksProvider.ldapServiceWith(LdapService.Name("ldap1"))(newRequestId()) should be(Some(
                LdapServiceMock(Set(LdapServiceMock.LdapUserMock(User.Id("Tom"), Set(group("group1"), group("group2")))))
              ))
              rorInstance.mocksProvider.ldapServiceWith(LdapService.Name("ldap2"))(newRequestId()) should be(None)

              rorInstance.mocksProvider.externalAuthenticationServiceWith(ExternalAuthenticationService.Name("ext1"))(newRequestId()) should be(Some(
                ExternalAuthenticationServiceMock(Set(
                  ExternalAuthenticationServiceMock.ExternalAuthenticationUserMock(User.Id("Matt")),
                  ExternalAuthenticationServiceMock.ExternalAuthenticationUserMock(User.Id("Emily")),
                ))
              ))
              rorInstance.mocksProvider.externalAuthenticationServiceWith(ExternalAuthenticationService.Name("ext2"))(newRequestId()) should be(None)

              rorInstance.mocksProvider.externalAuthorizationServiceWith(ExternalAuthorizationService.Name("grp1"))(newRequestId()) should be(Some(
                ExternalAuthorizationServiceMock(Set(
                  ExternalAuthorizationServiceMock.ExternalAuthorizationServiceUserMock(
                    id = User.Id("Bruce"),
                    groups = Set(group("group3"), group("group4"))
                  )
                ))
              ))
              rorInstance.mocksProvider.externalAuthorizationServiceWith(ExternalAuthorizationService.Name("grp2"))(newRequestId()) should be(None)
            }
          }
          "load test engine as invalidated" when {
            "the expiration timestamp exceeded" in withReadonlyRest({
              val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
              val indexSettingsFile = "readonlyrest_index.yml"
              lazy val expirationTimestamp = testClock.instant().minusSeconds(100)

              val mockedIndexDocumentManager = mock[IndexDocumentManager]
              mockGettingMainSettings(mockedIndexDocumentManager, resourcesPath + indexSettingsFile)
              mockGettingTestSettings(
                mockedIndexDocumentManager,
                circeJsonFrom(
                  s"""{
                     |  "settings": "${escapeJava(testSettings1.rawYaml)}",
                     |  "expiration_ttl_millis": "100000",
                     |  "expiration_timestamp": "${expirationTimestamp.toString}",
                     |  "auth_services_mocks": $configuredAuthServicesMocksJson
                     |}""".stripMargin
                )
              )

              val coreFactory = mock[CoreFactory]
              mockCoreFactory(coreFactory, resourcesPath + indexSettingsFile)

              implicit val systemContext: SystemContext = createSystemContext(refreshInterval = Some(10 seconds))
              (
                readonlyRestBoot(coreFactory, mockedIndexDocumentManager),
                forceCreateEsConfigBasedRorSettings(resourcesPath)
              )
            }) { rorInstance =>
              rorInstance.engines.value.impersonatorsEngine should be(Option.empty)

              rorInstance.currentTestSettings()(newRequestId()).runSyncUnsafe() should be(
                TestSettings.Invalidated(
                  recent = testSettings1,
                  configuredTtl = (100 seconds).toRefinedPositiveUnsafe
                )
              )
            }
          }
        }
        "index is not accessible" should {
          "fallback to not configured" in withReadonlyRest({
            val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
            val indexSettingsFile = "readonlyrest_index.yml"

            val mockedIndexDocumentManager = mock[IndexDocumentManager]
            mockGettingMainSettings(mockedIndexDocumentManager, resourcesPath + indexSettingsFile)
            mockGettingTestSettingsReturnsError(mockedIndexDocumentManager, error = DocumentNotFound)

            val coreFactory = mock[CoreFactory]
            mockCoreFactory(coreFactory, resourcesPath + indexSettingsFile)

            implicit val systemContext: SystemContext = createSystemContext(refreshInterval = Some(5 seconds))
            (
              readonlyRestBoot(coreFactory, mockedIndexDocumentManager),
              forceCreateEsConfigBasedRorSettings(resourcesPath)
            )
          }) { rorInstance =>
            rorInstance.engines.value.impersonatorsEngine should be(Option.empty)

            rorInstance.currentTestSettings()(newRequestId()).runSyncUnsafe() should be(TestSettings.NotSet)
          }
        }
        "settings structure is not valid" should {
          "fallback to not configured" in withReadonlyRest({
            val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
            val indexSettingsFile = "readonlyrest_index.yml"
            lazy val expirationTimestamp = testClock.instant().minusSeconds(100)

            val mockedIndexDocumentManager = mock[IndexDocumentManager]
            mockGettingMainSettings(mockedIndexDocumentManager, resourcesPath + indexSettingsFile)
            mockGettingTestSettings(
              mockedIndexDocumentManager,
              circeJsonFrom(
                s"""{
                   |  "settings": "malformed_settings",
                   |  "expiration_ttl_millis": "100000",
                   |  "expiration_timestamp": "${expirationTimestamp.toString}",
                   |  "auth_services_mocks": $configuredAuthServicesMocksJson
                   |}""".stripMargin
              )
            )

            val coreFactory = mock[CoreFactory]
            mockCoreFactory(coreFactory, resourcesPath + indexSettingsFile)

            implicit val systemContext: SystemContext = createSystemContext(refreshInterval = Some(5 seconds))
            (
              readonlyRestBoot(coreFactory, mockedIndexDocumentManager),
              forceCreateEsConfigBasedRorSettings(resourcesPath)
            )
          }) { rorInstance =>
            rorInstance.engines.value.impersonatorsEngine should be(Option.empty)

            rorInstance.currentTestSettings()(newRequestId()).runSyncUnsafe() should be(TestSettings.NotSet)
          }
        }
        "settings structure is valid, rule is malformed and cannot start engine" should {
          "fallback to invalidated settings" in withReadonlyRest({
            val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
            val indexSettingsFile = "readonlyrest_index.yml"

            val mockedIndexDocumentManager = mock[IndexDocumentManager]
            mockGettingMainSettings(mockedIndexDocumentManager, resourcesPath + indexSettingsFile)
            mockGettingTestSettings(
              mockedIndexDocumentManager,
              circeJsonFrom(
                s"""{
                   |  "settings": "${escapeJava(testSettingsMalformed.rawYaml)}",
                   |  "expiration_ttl_millis": "100000",
                   |  "expiration_timestamp": "${testClock.instant().plusSeconds(100).toString}",
                   |  "auth_services_mocks": $configuredAuthServicesMocksJson
                   |}""".stripMargin
              )
            )

            val coreFactory = mock[CoreFactory]
            mockCoreFactory(coreFactory, resourcesPath + indexSettingsFile)
            mockFailedCoreFactory(coreFactory, testSettingsMalformed)

            implicit val systemContext: SystemContext = createSystemContext(refreshInterval = Some(5 seconds))
            (
              readonlyRestBoot(coreFactory, mockedIndexDocumentManager),
              forceCreateEsConfigBasedRorSettings(resourcesPath)
            )
          }) { rorInstance =>
            rorInstance.engines.value.impersonatorsEngine should be(Option.empty)
            rorInstance.currentTestSettings()(newRequestId()).runSyncUnsafe() should be(
              TestSettings.Invalidated(
                recent = testSettingsMalformed,
                configuredTtl = (100 seconds).toRefinedPositiveUnsafe
              )
            )
          }
        }
      }
      "can be loaded on demand" when {
        "there is no previous engine" in withReadonlyRestExt({
          val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
          val indexSettingsFile = "readonlyrest_index.yml"

          val mockedIndexDocumentManager = mock[IndexDocumentManager]
          mockGettingMainSettings(mockedIndexDocumentManager, resourcesPath + indexSettingsFile)
          mockGettingTestSettingsReturnsError(mockedIndexDocumentManager, error = DocumentNotFound)

          val coreFactory = mock[CoreFactory]
          mockCoreFactory(coreFactory, resourcesPath + indexSettingsFile)
          mockCoreFactory(coreFactory, testSettings1, mockEnabledAccessControl, RorDependencies.noOp, None)

          implicit val systemContext: SystemContext = createSystemContext(refreshInterval = Some(10 seconds))
          (
            readonlyRestBoot(coreFactory, mockedIndexDocumentManager),
            forceCreateEsConfigBasedRorSettings(resourcesPath),
            mockedIndexDocumentManager
          )
        }) { case (rorInstance, mockedIndexDocumentManager) =>
          rorInstance.engines.value.impersonatorsEngine should be(Option.empty)

          rorInstance.currentTestSettings()(newRequestId()).runSyncUnsafe() should be(TestSettings.NotSet)

          (mockedIndexDocumentManager.saveDocumentJson _)
            .expects(
              where {
                (index: IndexName.Full, id: String, document: Json) =>
                  index == fullIndexName(".readonlyrest") &&
                    id == "2" &&
                    document.hcursor.get[String]("settings").toOption.contains(testSettings1.rawYaml) &&
                    document.hcursor.get[String]("expiration_ttl_millis").toOption.contains("60000") &&
                    document.hcursor.downField("expiration_timestamp").succeeded &&
                    document.hcursor.downField("auth_services_mocks").succeeded
              }
            )
            .repeated(1)
            .returns(Task.now(Right(())))

          val testEngineReloadResult = rorInstance
            .forceReloadTestSettingsEngine(testSettings1, (1 minute).toRefinedPositiveUnsafe)(newRequestId())
            .runSyncUnsafe()

          testEngineReloadResult.value shouldBe a[TestSettings.Present]
          rorInstance.engines.value.impersonatorsEngine.value.core.accessControl shouldBe a[AccessControlListLoggingDecorator]

          val testSettingsEngine = rorInstance.currentTestSettings()(newRequestId()).runSyncUnsafe()
          testSettingsEngine shouldBe a[TestSettings.Present]
          Option(testSettingsEngine.asInstanceOf[TestSettings.Present]).map(i => (i.rawSettings, i.configuredTtl.value)) should be {
            (testSettings1, 1 minute).some
          }
        }
        "there is previous engine" when {
          "same settings and ttl" in withReadonlyRestExt({
            val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
            val indexSettingsFile = "readonlyrest_index.yml"

            val mockedIndexDocumentManager = mock[IndexDocumentManager]
            mockGettingMainSettings(mockedIndexDocumentManager, resourcesPath + indexSettingsFile)
            mockGettingTestSettingsReturnsError(mockedIndexDocumentManager, error = DocumentNotFound)

            val coreFactory = mock[CoreFactory]
            mockCoreFactory(coreFactory, resourcesPath + indexSettingsFile)
            mockCoreFactory(coreFactory, testSettings1, mockEnabledAccessControl, RorDependencies.noOp, None)

            implicit val systemContext: SystemContext = createSystemContext(refreshInterval = Some(10 seconds))
            (
              readonlyRestBoot(coreFactory, mockedIndexDocumentManager),
              forceCreateEsConfigBasedRorSettings(resourcesPath),
              mockedIndexDocumentManager
            )
          }) { case (rorInstance, mockedIndexDocumentManager) =>
            rorInstance.engines.value.impersonatorsEngine should be(Option.empty)
            rorInstance.currentTestSettings()(newRequestId()).runSyncUnsafe() should be(TestSettings.NotSet)

            (mockedIndexDocumentManager.saveDocumentJson _)
              .expects(
                where {
                  (index: IndexName.Full, id: String, document: Json) =>
                    index == fullIndexName(".readonlyrest") &&
                      id == "2" &&
                      document.hcursor.get[String]("settings").toOption.contains(testSettings1.rawYaml) &&
                      document.hcursor.get[String]("expiration_ttl_millis").toOption.contains("60000") &&
                      document.hcursor.downField("expiration_timestamp").succeeded &&
                      document.hcursor.downField("auth_services_mocks").succeeded
                }
              )
              .repeated(2)
              .returns(Task.now(Right(())))

            val testEngineReloadResult1stAttempt = rorInstance
              .forceReloadTestSettingsEngine(testSettings1, (1 minute).toRefinedPositiveUnsafe)(newRequestId())
              .runSyncUnsafe()

            testEngineReloadResult1stAttempt.value shouldBe a[TestSettings.Present]
            rorInstance.engines.value.impersonatorsEngine.value.core.accessControl shouldBe a[AccessControlListLoggingDecorator]

            val testSettingsEngine = rorInstance.currentTestSettings()(newRequestId()).runSyncUnsafe()
            testSettingsEngine shouldBe a[TestSettings.Present]
            Option(testSettingsEngine.asInstanceOf[TestSettings.Present]).map(i => (i.rawSettings, i.configuredTtl.value)) should be {
              (testSettings1, 1 minute).some
            }

            val testEngine1Expiration = testSettingsEngine.asInstanceOf[TestSettings.Present].validTo

            val testEngineReloadResult2ndAttempt = rorInstance
              .forceReloadTestSettingsEngine(testSettings1, (1 minute).toRefinedPositiveUnsafe)(newRequestId())
              .runSyncUnsafe()

            testEngineReloadResult2ndAttempt.value shouldBe a[TestSettings.Present]
            rorInstance.engines.value.impersonatorsEngine.value.core.accessControl shouldBe a[AccessControlListLoggingDecorator]

            val testSettingsEngineAfterReload = rorInstance.currentTestSettings()(newRequestId()).runSyncUnsafe()
            testSettingsEngineAfterReload shouldBe a[TestSettings.Present]
            Option(testSettingsEngineAfterReload.asInstanceOf[TestSettings.Present]).map(i => (i.rawSettings, i.configuredTtl.value)) should be {
              (testSettings1, 1 minute).some
            }

            val testEngine2Expiration = testSettingsEngineAfterReload.asInstanceOf[TestSettings.Present].validTo

            testEngine2Expiration.isAfter(testEngine1Expiration) should be(true)
          }
          "different ttl" in withReadonlyRestExt({
            val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
            val indexSettingsFile = "readonlyrest_index.yml"

            val mockedIndexDocumentManager = mock[IndexDocumentManager]
            mockGettingMainSettings(mockedIndexDocumentManager, resourcesPath + indexSettingsFile)
            mockGettingTestSettingsReturnsError(mockedIndexDocumentManager, error = DocumentNotFound)

            val coreFactory = mock[CoreFactory]
            mockCoreFactory(coreFactory, resourcesPath + indexSettingsFile)
            mockCoreFactory(coreFactory, testSettings1, mockEnabledAccessControl, RorDependencies.noOp, None)

            implicit val systemContext: SystemContext = createSystemContext(refreshInterval = Some(10 seconds))
            (
              readonlyRestBoot(coreFactory, mockedIndexDocumentManager),
              forceCreateEsConfigBasedRorSettings(resourcesPath),
              mockedIndexDocumentManager
            )
          }) { case (rorInstance, mockedIndexDocumentManager) =>
            rorInstance.engines.value.impersonatorsEngine should be(Option.empty)
            rorInstance.currentTestSettings()(newRequestId()).runSyncUnsafe() should be(TestSettings.NotSet)

            (mockedIndexDocumentManager.saveDocumentJson _)
              .expects(
                where {
                  (index: IndexName.Full, id: String, document: Json) =>
                    index == fullIndexName(".readonlyrest") &&
                      id == "2" &&
                      document.hcursor.get[String]("settings").toOption.contains(testSettings1.rawYaml) &&
                      document.hcursor.get[String]("expiration_ttl_millis").toOption.contains("600000") &&
                      document.hcursor.downField("expiration_timestamp").succeeded &&
                      document.hcursor.downField("auth_services_mocks").succeeded
                }
              )
              .repeated(1)
              .returns(Task.now(Right(())))

            val testEngineReloadResult1stAttempt = rorInstance
              .forceReloadTestSettingsEngine(testSettings1, (10 minute).toRefinedPositiveUnsafe)(newRequestId())
              .runSyncUnsafe()

            testEngineReloadResult1stAttempt.value shouldBe a[TestSettings.Present]
            rorInstance.engines.value.impersonatorsEngine.value.core.accessControl shouldBe a[AccessControlListLoggingDecorator]

            val testSettingsEngine = rorInstance.currentTestSettings()(newRequestId()).runSyncUnsafe()
            testSettingsEngine shouldBe a[TestSettings.Present]
            Option(testSettingsEngine.asInstanceOf[TestSettings.Present])
              .map(i => (i.rawSettings, i.configuredTtl.value)) should be((testSettings1, 10 minute).some)

            val testEngine1Expiration = testSettingsEngine.asInstanceOf[TestSettings.Present].validTo

            (mockedIndexDocumentManager.saveDocumentJson _)
              .expects(
                where {
                  (config: IndexName.Full, id: String, document: Json) =>
                    config == fullIndexName(".readonlyrest") &&
                      id == "2" &&
                      document.hcursor.get[String]("settings").toOption.contains(testSettings1.rawYaml) &&
                      document.hcursor.get[String]("expiration_ttl_millis").toOption.contains("300000") &&
                      document.hcursor.downField("expiration_timestamp").succeeded &&
                      document.hcursor.downField("auth_services_mocks").succeeded
                }
              )
              .repeated(1)
              .returns(Task.now(Right(())))

            val testEngineReloadResult2ndAttempt = rorInstance
              .forceReloadTestSettingsEngine(testSettings1, (5 minute).toRefinedPositiveUnsafe)(newRequestId())
              .runSyncUnsafe()

            testEngineReloadResult2ndAttempt.value shouldBe a[TestSettings.Present]
            rorInstance.engines.value.impersonatorsEngine.value.core.accessControl shouldBe a[AccessControlListLoggingDecorator]

            val testSettingsEngineAfterReload = rorInstance.currentTestSettings()(newRequestId()).runSyncUnsafe()
            testSettingsEngineAfterReload shouldBe a[TestSettings.Present]
            Option(testSettingsEngineAfterReload.asInstanceOf[TestSettings.Present])
              .map(i => (i.rawSettings, i.configuredTtl.value)) should be((testSettings1, 5 minute).some)

            val testEngine2Expiration = testSettingsEngineAfterReload.asInstanceOf[TestSettings.Present].validTo

            testEngine2Expiration.isAfter(testEngine1Expiration) should be(false)
          }
        }
        "different settings is being loaded" in withReadonlyRestExt({
          val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
          val indexSettingsFile = "readonlyrest_index.yml"

          val mockedIndexDocumentManager = mock[IndexDocumentManager]
          mockGettingMainSettings(mockedIndexDocumentManager, resourcesPath + indexSettingsFile)
          mockGettingTestSettingsReturnsError(mockedIndexDocumentManager, error = DocumentNotFound)

          val coreFactory = mock[CoreFactory]
          mockCoreFactory(coreFactory, resourcesPath + indexSettingsFile)
          mockCoreFactory(coreFactory, testSettings1, mockEnabledAccessControl, RorDependencies.noOp, None)
          mockCoreFactory(coreFactory, testSettings2, mockEnabledAccessControl, RorDependencies.noOp, None)

          implicit val systemContext: SystemContext = createSystemContext(refreshInterval = Some(10 seconds))
          (
            readonlyRestBoot(coreFactory, mockedIndexDocumentManager),
            forceCreateEsConfigBasedRorSettings(resourcesPath),
            mockedIndexDocumentManager
          )
        }) { case (rorInstance, mockedIndexDocumentManager) =>
          rorInstance.engines.value.impersonatorsEngine should be(Option.empty)
          rorInstance.currentTestSettings()(newRequestId()).runSyncUnsafe() should be(TestSettings.NotSet)

          (mockedIndexDocumentManager.saveDocumentJson _)
            .expects(
              where {
                (index: IndexName.Full, id: String, document: Json) =>
                  index == fullIndexName(".readonlyrest") &&
                    id == "2" &&
                    document.hcursor.get[String]("settings").toOption.contains(testSettings1.rawYaml) &&
                    document.hcursor.get[String]("expiration_ttl_millis").toOption.contains("60000") &&
                    document.hcursor.downField("expiration_timestamp").succeeded &&
                    document.hcursor.downField("auth_services_mocks").succeeded
              }
            )
            .repeated(1)
            .returns(Task.now(Right(())))

          val testEngineReloadResult1stAttempt = rorInstance
            .forceReloadTestSettingsEngine(testSettings1, (1 minute).toRefinedPositiveUnsafe)(newRequestId())
            .runSyncUnsafe()

          testEngineReloadResult1stAttempt.value shouldBe a[TestSettings.Present]
          rorInstance.engines.value.impersonatorsEngine.value.core.accessControl shouldBe a[AccessControlListLoggingDecorator]

          val testSettingsEngine = rorInstance.currentTestSettings()(newRequestId()).runSyncUnsafe()
          testSettingsEngine shouldBe a[TestSettings.Present]
          Option(testSettingsEngine.asInstanceOf[TestSettings.Present]).map(i => (i.rawSettings, i.configuredTtl.value)) should be {
            (testSettings1, 1 minute).some
          }

          val testEngine1Expiration = testSettingsEngine.asInstanceOf[TestSettings.Present].validTo

          (mockedIndexDocumentManager.saveDocumentJson _)
            .expects(
              where {
                (index: IndexName.Full, id: String, document: Json) =>
                  index == fullIndexName(".readonlyrest") &&
                    id == "2" &&
                    document.hcursor.get[String]("settings").toOption.contains(testSettings2.rawYaml) &&
                    document.hcursor.get[String]("expiration_ttl_millis").toOption.contains("120000") &&
                    document.hcursor.downField("expiration_timestamp").succeeded &&
                    document.hcursor.downField("auth_services_mocks").succeeded
              }
            )
            .repeated(1)
            .returns(Task.now(Right(())))

          val testEngineReloadResult2ndAttempt = rorInstance
            .forceReloadTestSettingsEngine(testSettings2, (2 minutes).toRefinedPositiveUnsafe)(newRequestId())
            .runSyncUnsafe()

          testEngineReloadResult2ndAttempt.value shouldBe a[TestSettings.Present]
          rorInstance.engines.value.impersonatorsEngine.value.core.accessControl shouldBe a[AccessControlListLoggingDecorator]

          val testSettingsEngineAfterReload = rorInstance.currentTestSettings()(newRequestId()).runSyncUnsafe()
          testSettingsEngineAfterReload shouldBe a[TestSettings.Present]
          Option(testSettingsEngineAfterReload.asInstanceOf[TestSettings.Present])
            .map(i => (i.rawSettings, i.configuredTtl.value)) should be((testSettings2, 2 minutes).some)

          val testEngine2Expiration = testSettingsEngineAfterReload.asInstanceOf[TestSettings.Present].validTo

          testEngine2Expiration.isAfter(testEngine1Expiration) should be(true)
        }
      }
      "can be reloaded if index settings changes" when {
        "new settings and expiration time has not exceeded" in withReadonlyRestExt({
          val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
          val indexSettingsFile = "readonlyrest_index.yml"

          val mockedIndexDocumentManager = mock[IndexDocumentManager]
          mockGettingMainSettings(mockedIndexDocumentManager, resourcesPath + indexSettingsFile, AttemptCount.Exact(2))
          mockGettingTestSettingsReturnsError(mockedIndexDocumentManager, error = DocumentNotFound)

          val coreFactory = mock[CoreFactory]
          mockCoreFactory(coreFactory, resourcesPath + indexSettingsFile)
          mockCoreFactory(coreFactory, testSettings1, mockEnabledAccessControl, RorDependencies.noOp, None)

          implicit val systemContext: SystemContext = createSystemContext(refreshInterval = Some(2 seconds))
          (
            readonlyRestBoot(coreFactory, mockedIndexDocumentManager),
            forceCreateEsConfigBasedRorSettings(resourcesPath),
            mockedIndexDocumentManager
          )
        }) { case (rorInstance, mockedIndexDocumentManager) =>

          lazy val expirationTimestamp = testClock.instant().plusSeconds(100)
          (mockedIndexDocumentManager.documentAsJson _)
            .expects(fullIndexName(".readonlyrest"), "2")
            .repeated(1)
            .returns(Task.now(Right(circeJsonFrom(
              s"""{
                 |  "settings": "${escapeJava(testSettings1.rawYaml)}",
                 |  "expiration_ttl_millis": "100000",
                 |  "expiration_timestamp": "${expirationTimestamp.toString}",
                 |  "auth_services_mocks": $notConfiguredAuthServicesMocksJson
                 |}""".stripMargin
            ))))

          rorInstance.engines.value.impersonatorsEngine should be(Option.empty)
          rorInstance.currentTestSettings()(newRequestId()).runSyncUnsafe() should be(TestSettings.NotSet)

          Task.sleep(5 seconds).runSyncUnsafe()

          rorInstance.engines.value.impersonatorsEngine.value.core.accessControl shouldBe a[AccessControlListLoggingDecorator]

          rorInstance.currentTestSettings()(newRequestId()).runSyncUnsafe() should be(
            TestSettings.Present(
              dependencies = RorDependencies.noOp,
              rawSettings = testSettings1,
              configuredTtl = (100 seconds).toRefinedPositiveUnsafe,
              validTo = expirationTimestamp
            )
          )
        }
        "same settings and the ttl has changed" in withReadonlyRestExt({
          val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
          val indexSettingsFile = "readonlyrest_index.yml"
          lazy val expirationTimestamp = testClock.instant().plusSeconds(100)

          val mockedIndexDocumentManager = mock[IndexDocumentManager]
          mockGettingMainSettings(mockedIndexDocumentManager, resourcesPath + indexSettingsFile, AttemptCount.Exact(2))
          mockGettingTestSettings(
            mockedIndexDocumentManager,
            circeJsonFrom(
              s"""{
                 |  "settings": "${escapeJava(testSettings1.rawYaml)}",
                 |  "expiration_ttl_millis": "100000",
                 |  "expiration_timestamp": "${expirationTimestamp.toString}",
                 |  "auth_services_mocks": $notConfiguredAuthServicesMocksJson
                 |}""".stripMargin
            )
          )
          val coreFactory = mock[CoreFactory]
          mockCoreFactory(coreFactory, resourcesPath + indexSettingsFile)
          mockCoreFactory(coreFactory, testSettings1, mockEnabledAccessControl, RorDependencies.noOp, None)

          implicit val systemContext: SystemContext = createSystemContext(refreshInterval = Some(2 seconds))
          (
            readonlyRestBoot(coreFactory, mockedIndexDocumentManager),
            forceCreateEsConfigBasedRorSettings(resourcesPath),
            (mockedIndexDocumentManager, expirationTimestamp)
          )
        }) { case (rorInstance, (mockedIndexDocumentManager, expirationTimestamp)) =>
          rorInstance.currentTestSettings()(newRequestId()).runSyncUnsafe() should be(
            TestSettings.Present(
              dependencies = RorDependencies.noOp,
              rawSettings = testSettings1,
              configuredTtl = (100 seconds).toRefinedPositiveUnsafe,
              validTo = expirationTimestamp
            )
          )

          val expirationTimestamp2 = testClock.instant().plusSeconds(200)
          (mockedIndexDocumentManager.documentAsJson _)
            .expects(fullIndexName(".readonlyrest"), "2")
            .repeated(1)
            .returns(Task.now(Right(circeJsonFrom(
              s"""{
                 |  "settings": "${escapeJava(testSettings1.rawYaml)}",
                 |  "expiration_ttl_millis": "200000",
                 |  "expiration_timestamp": "${expirationTimestamp2.toString}",
                 |  "auth_services_mocks": $notConfiguredAuthServicesMocksJson
                 |}""".stripMargin
            ))))

          Task.sleep(5 seconds).runSyncUnsafe()

          rorInstance.engines.value.impersonatorsEngine.value.core.accessControl shouldBe a[AccessControlListLoggingDecorator]

          rorInstance.currentTestSettings()(newRequestId()).runSyncUnsafe() should be(
            TestSettings.Present(
              dependencies = RorDependencies.noOp,
              rawSettings = testSettings1,
              configuredTtl = (200 seconds).toRefinedPositiveUnsafe,
              validTo = expirationTimestamp2
            )
          )
        }
        "same settings and the expiration time has changed" in withReadonlyRestExt({
          val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
          val indexSettingsFile = "readonlyrest_index.yml"
          lazy val expirationTimestamp = testClock.instant().plusSeconds(100)

          val mockedIndexDocumentManager = mock[IndexDocumentManager]
          mockGettingMainSettings(mockedIndexDocumentManager, resourcesPath + indexSettingsFile, AttemptCount.Exact(2))
          mockGettingTestSettings(
            mockedIndexDocumentManager,
            circeJsonFrom(
              s"""{
                 |  "settings": "${escapeJava(testSettings1.rawYaml)}",
                 |  "expiration_ttl_millis": "100000",
                 |  "expiration_timestamp": "${expirationTimestamp.toString}",
                 |  "auth_services_mocks": $notConfiguredAuthServicesMocksJson
                 |}""".stripMargin
            )
          )

          val coreFactory = mock[CoreFactory]
          mockCoreFactory(coreFactory, resourcesPath + indexSettingsFile)
          mockCoreFactory(coreFactory, testSettings1, mockEnabledAccessControl, RorDependencies.noOp, None)

          implicit val systemContext: SystemContext = createSystemContext(refreshInterval = Some(2 seconds))
          (
            readonlyRestBoot(coreFactory, mockedIndexDocumentManager),
            forceCreateEsConfigBasedRorSettings(resourcesPath),
            (mockedIndexDocumentManager, expirationTimestamp)
          )
        }) { case (rorInstance, (mockedIndexDocumentManager, expirationTimestamp)) =>

          rorInstance.currentTestSettings()(newRequestId()).runSyncUnsafe() should be(
            TestSettings.Present(
              dependencies = RorDependencies.noOp,
              rawSettings = testSettings1,
              configuredTtl = (100 seconds).toRefinedPositiveUnsafe,
              validTo = expirationTimestamp
            )
          )

          val expirationTimestamp2 = testClock.instant().plusSeconds(100)
          (mockedIndexDocumentManager.documentAsJson _)
            .expects(fullIndexName(".readonlyrest"), "2")
            .repeated(1)
            .returns(Task.now(Right(circeJsonFrom(
              s"""{
                 |  "settings": "${escapeJava(testSettings1.rawYaml)}",
                 |  "expiration_ttl_millis": "100000",
                 |  "expiration_timestamp": "${expirationTimestamp2.toString}",
                 |  "auth_services_mocks": $notConfiguredAuthServicesMocksJson
                 |}""".stripMargin
            ))))

          Task.sleep(5 seconds).runSyncUnsafe()

          rorInstance.engines.value.impersonatorsEngine.value.core.accessControl shouldBe a[AccessControlListLoggingDecorator]

          rorInstance.currentTestSettings()(newRequestId()).runSyncUnsafe() should be(
            TestSettings.Present(
              dependencies = RorDependencies.noOp,
              rawSettings = testSettings1,
              configuredTtl = (100 seconds).toRefinedPositiveUnsafe,
              validTo = expirationTimestamp2
            )
          )
        }
        "new settings and has already expired" in withReadonlyRestExt({
          val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
          val indexSettingsFile = "readonlyrest_index.yml"
          lazy val expirationTimestamp = testClock.instant().plusSeconds(100)

          val mockedIndexDocumentManager = mock[IndexDocumentManager]
          mockGettingMainSettings(mockedIndexDocumentManager, resourcesPath + indexSettingsFile, AttemptCount.Exact(2))
          mockGettingTestSettings(
            mockedIndexDocumentManager,
            circeJsonFrom(
              s"""{
                 |  "settings": "${escapeJava(testSettings1.rawYaml)}",
                 |  "expiration_ttl_millis": "100000",
                 |  "expiration_timestamp": "${expirationTimestamp.toString}",
                 |  "auth_services_mocks": $notConfiguredAuthServicesMocksJson
                 |}""".stripMargin
            )
          )

          val coreFactory = mock[CoreFactory]
          mockCoreFactory(coreFactory, resourcesPath + indexSettingsFile)
          mockCoreFactory(coreFactory, testSettings1, mockEnabledAccessControl, RorDependencies.noOp, None)

          implicit val systemContext: SystemContext = createSystemContext(refreshInterval = Some(2 seconds))
          (
            readonlyRestBoot(coreFactory, mockedIndexDocumentManager),
            forceCreateEsConfigBasedRorSettings(resourcesPath),
            (mockedIndexDocumentManager, expirationTimestamp)
          )
        }) { case (rorInstance, (mockedIndexDocumentManager, expirationTimestamp)) =>

          rorInstance.currentTestSettings()(newRequestId()).runSyncUnsafe() should be(
            TestSettings.Present(
              dependencies = RorDependencies.noOp,
              rawSettings = testSettings1,
              configuredTtl = (100 seconds).toRefinedPositiveUnsafe,
              validTo = expirationTimestamp
            )
          )

          val expirationTimestamp2 = testClock.instant().minusSeconds(1)
          (mockedIndexDocumentManager.documentAsJson _)
            .expects(fullIndexName(".readonlyrest"), "2")
            .repeated(1)
            .returns(Task.delay(Right(circeJsonFrom(
              s"""{
                 |  "settings": "${escapeJava(testSettings2.rawYaml)}",
                 |  "expiration_ttl_millis": "200000",
                 |  "expiration_timestamp": "${expirationTimestamp2.toString}",
                 |  "auth_services_mocks": $notConfiguredAuthServicesMocksJson
                 |}""".stripMargin
            ))))

          Task.sleep(5 seconds).runSyncUnsafe()

          rorInstance.engines.value.impersonatorsEngine should be(Option.empty)

          rorInstance.currentTestSettings()(newRequestId()).runSyncUnsafe() should be(
            TestSettings.Invalidated(
              recent = testSettings2,
              configuredTtl = (200 seconds).toRefinedPositiveUnsafe
            )
          )
        }
      }
      "should be automatically unloaded" when {
        "engine ttl has reached" in withReadonlyRestExt({
          val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
          val indexSettingsFile = "readonlyrest_index.yml"

          val mockedIndexDocumentManager = mock[IndexDocumentManager]
          mockGettingMainSettings(mockedIndexDocumentManager, resourcesPath + indexSettingsFile)
          mockGettingTestSettingsReturnsError(mockedIndexDocumentManager, error = DocumentNotFound)

          val coreFactory = mock[CoreFactory]
          mockCoreFactory(coreFactory, resourcesPath + indexSettingsFile)
          mockCoreFactory(coreFactory, testSettings1, mockEnabledAccessControl, RorDependencies.noOp, None)

          implicit val systemContext: SystemContext = createSystemContext(refreshInterval = Some(0 seconds))
          (
            readonlyRestBoot(coreFactory, mockedIndexDocumentManager),
            forceCreateEsConfigBasedRorSettings(resourcesPath),
            mockedIndexDocumentManager
          )
        }) { case (rorInstance, mockedIndexDocumentManager) =>

          rorInstance.engines.value.impersonatorsEngine should be(Option.empty)
          rorInstance.currentTestSettings()(newRequestId()).runSyncUnsafe() should be(TestSettings.NotSet)

          (mockedIndexDocumentManager.saveDocumentJson _)
            .expects(
              where {
                (index: IndexName.Full, id: String, document: Json) =>
                  index == fullIndexName(".readonlyrest") &&
                    id == "2" &&
                    document.hcursor.get[String]("settings").toOption.contains(testSettings1.rawYaml) &&
                    document.hcursor.get[String]("expiration_ttl_millis").toOption.contains("3000") &&
                    document.hcursor.downField("expiration_timestamp").succeeded &&
                    document.hcursor.downField("auth_services_mocks").succeeded
              }
            )
            .repeated(1)
            .returns(Task.now(Right(())))

          val testEngineReloadResult = rorInstance
            .forceReloadTestSettingsEngine(testSettings1, (3 seconds).toRefinedPositiveUnsafe)(newRequestId())
            .runSyncUnsafe()

          testEngineReloadResult.value shouldBe a[TestSettings.Present]
          rorInstance.engines.value.impersonatorsEngine.value.core.accessControl shouldBe a[AccessControlListLoggingDecorator]

          Task.sleep(5 seconds).runSyncUnsafe()

          rorInstance.engines.value.impersonatorsEngine should be(Option.empty)
          rorInstance.currentTestSettings()(newRequestId()).runSyncUnsafe() should be(
            TestSettings.Invalidated(testSettings1, (3 seconds).toRefinedPositiveUnsafe)
          )
        }
      }
      "can be invalidated by user" in withReadonlyRestExt({
        val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
        val indexSettingsFile = "readonlyrest_index.yml"

        val mockedIndexDocumentManager = mock[IndexDocumentManager]
        mockGettingMainSettings(mockedIndexDocumentManager, resourcesPath + indexSettingsFile)
        mockGettingTestSettingsReturnsError(mockedIndexDocumentManager, error = DocumentNotFound)

        val coreFactory = mock[CoreFactory]
        mockCoreFactory(coreFactory, resourcesPath + indexSettingsFile)
        mockCoreFactory(coreFactory, testSettings1, mockEnabledAccessControl, RorDependencies.noOp, None)

        implicit val systemContext: SystemContext = createSystemContext(refreshInterval = Some(10 seconds))
        (
          readonlyRestBoot(coreFactory, mockedIndexDocumentManager),
          forceCreateEsConfigBasedRorSettings(resourcesPath),
          mockedIndexDocumentManager)
      }) { case (rorInstance, mockedIndexDocumentManager) =>
        rorInstance.engines.value.impersonatorsEngine should be(Option.empty)
        rorInstance.currentTestSettings()(newRequestId()).runSyncUnsafe() should be(TestSettings.NotSet)

        (mockedIndexDocumentManager.saveDocumentJson _)
          .expects(
            where {
              (index: IndexName.Full, id: String, document: Json) =>
                index == fullIndexName(".readonlyrest") &&
                  id == "2" &&
                  document.hcursor.get[String]("settings").toOption.contains(testSettings1.rawYaml) &&
                  document.hcursor.get[String]("expiration_ttl_millis").toOption.contains("60000") &&
                  document.hcursor.downField("expiration_timestamp").succeeded &&
                  document.hcursor.downField("auth_services_mocks").succeeded
            }
          )
          .repeated(1)
          .returns(Task.now(Right(())))

        val testEngineReloadResult = rorInstance
          .forceReloadTestSettingsEngine(testSettings1, (1 minute).toRefinedPositiveUnsafe)(newRequestId())
          .runSyncUnsafe()

        testEngineReloadResult.value shouldBe a[TestSettings.Present]
        rorInstance.engines.value.impersonatorsEngine.value.core.accessControl shouldBe a[AccessControlListLoggingDecorator]
        rorInstance.currentTestSettings()(newRequestId()).runSyncUnsafe() shouldBe a[TestSettings.Present]

        (mockedIndexDocumentManager.saveDocumentJson _)
          .expects(
            where {
              (config: IndexName.Full, id: String, document: Json) =>
                config == fullIndexName(".readonlyrest") &&
                  id == "2" &&
                  document.hcursor.get[String]("settings").toOption.contains(testSettings1.rawYaml) &&
                  document.hcursor.get[String]("expiration_ttl_millis").toOption.contains("60000") &&
                  document.hcursor.downField("expiration_timestamp").succeeded &&
                  document.hcursor.downField("auth_services_mocks").succeeded
            }
          )
          .repeated(1)
          .returns(Task.now(Right(())))

        rorInstance.invalidateTestSettingsEngine()(newRequestId()).runSyncUnsafe() should be(Right(()))

        rorInstance.engines.value.impersonatorsEngine should be(Option.empty)
        rorInstance.currentTestSettings()(newRequestId()).runSyncUnsafe() should be(
          TestSettings.Invalidated(recent = testSettings1, configuredTtl = (1 minute).toRefinedPositiveUnsafe)
        )
      }
      "should return error for invalidation" when {
        "cannot save invalidation timestamp in index" in withReadonlyRestExt(
          {
            val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
            val indexSettingsFile = "readonlyrest_index.yml"
            lazy val expirationTimestamp = testClock.instant().plusSeconds(100)

            val mockedIndexDocumentManager = mock[IndexDocumentManager]
            mockGettingMainSettings(mockedIndexDocumentManager, resourcesPath + indexSettingsFile)
            mockGettingTestSettings(
              mockedIndexDocumentManager,
              circeJsonFrom(
                s"""
                   |{
                   |  "settings": "${escapeJava(testSettings1.rawYaml)}",
                   |  "expiration_ttl_millis": "100000",
                   |  "expiration_timestamp": "${expirationTimestamp.toString}",
                   |  "auth_services_mocks": $notConfiguredAuthServicesMocksJson
                   |}
                   |""".stripMargin
              )
            )

            val coreFactory = mock[CoreFactory]
            mockCoreFactory(coreFactory, resourcesPath + indexSettingsFile)
            mockCoreFactory(coreFactory, testSettings1, mockEnabledAccessControl, RorDependencies.noOp, None)

            implicit val systemContext: SystemContext = createSystemContext(refreshInterval = Some(10 seconds))
            (
              readonlyRestBoot(coreFactory, mockedIndexDocumentManager),
              forceCreateEsConfigBasedRorSettings(resourcesPath),
              (mockedIndexDocumentManager, expirationTimestamp)
            )
          }
        ) { case (rorInstance, (mockedIndexDocumentManager, expirationTimestamp)) =>
          rorInstance.engines.value.impersonatorsEngine.value.core.accessControl shouldBe a[AccessControlListLoggingDecorator]
          rorInstance.currentTestSettings()(newRequestId()).runSyncUnsafe() should be(
            TestSettings.Present(
              dependencies = RorDependencies.noOp,
              rawSettings = testSettings1,
              configuredTtl = (100 seconds).toRefinedPositiveUnsafe,
              validTo = expirationTimestamp
            )
          )

          (mockedIndexDocumentManager.saveDocumentJson _)
            .expects(
              where {
                (index: IndexName.Full, id: String, document: Json) =>
                  index == fullIndexName(".readonlyrest") &&
                    id == "2" &&
                    document.hcursor.get[String]("settings").toOption.contains(testSettings1.rawYaml) &&
                    document.hcursor.get[String]("expiration_ttl_millis").toOption.contains("100000") &&
                    document.hcursor.get[String]("expiration_timestamp").toOption.exists(_ != expirationTimestamp.toString)
              }
            )
            .repeated(1)
            .returns(Task.now(Left(CannotWriteToIndex)))

          rorInstance.invalidateTestSettingsEngine()(newRequestId()).runSyncUnsafe() should be(
            Left(IndexSettingsInvalidationError.IndexSettingsSavingError(SavingSettingsError.SourceSpecificError(CannotSaveSettings)))
          )

          rorInstance.engines.value.impersonatorsEngine should be(Option.empty)
          rorInstance.currentTestSettings()(newRequestId()).runSyncUnsafe() should be(
            TestSettings.Invalidated(testSettings1, (100 seconds).toRefinedPositiveUnsafe)
          )
        }
      }
    }
    "not be able to be loaded" when {
      "max size of ROR settings is exceeded" in {
        implicit val systemContext: SystemContext = createSystemContext()
        val readonlyRest = readonlyRestBoot(mock[CoreFactory], mock[IndexDocumentManager])

        val esConfigBasedRorSettings = {
          val settings = forceCreateEsConfigBasedRorSettings("/boot_tests/forced_file_loading/")
          settings.copy(settingsSource = settings.settingsSource.copy(settingsMaxSize = Bytes(1)))
        }

        val result = readonlyRest.start(esConfigBasedRorSettings).runSyncUnsafe()
        inside(result) {
          case Left(StartingFailure(message, _)) =>
            message should include("ROR settings are malformed")
            message should include("The incoming YAML document exceeds the limit: 1 code points")
        }
      }
      "unable to setup data stream audit output" in {
        val dataStreamSinkConfig1 = AuditSink.Config.EsDataStreamBasedSink.default
        val dataStreamSinkConfig2 = dataStreamSinkConfig1.copy(
          auditCluster = AuditCluster.RemoteAuditCluster(NonEmptyList.one(Uri.parse("0.0.0.0")))
        )

        val coreFactory = mockCoreFactory(
          mockedCoreFactory = mock[CoreFactory],
          "/boot_tests/forced_file_loading_with_audit/readonlyrest.yml",
          mockEnabledAccessControl,
          RorDependencies(RorDependencies.Services.empty, LocalUsers.empty, NoOpImpersonationWarningsReader),
          Some(AuditingTool.AuditSettings(
            NonEmptyList.of(
              AuditSink.Enabled(dataStreamSinkConfig1),
              AuditSink.Enabled(dataStreamSinkConfig2)
            ),
            testEsNodeSettings
          ))
        )

        val dataStreamService1 = mockedDataSteamService(dataStreamExists = false, ilmCreationResult = NotAcknowledged)
        val dataStreamService2 = mockedDataSteamService(dataStreamExists = false, ilmCreationResult = Acknowledged, componentTemplateResult = NotAcknowledged)

        val auditSinkServiceCreator = mock[DataStreamAndIndexBasedAuditSinkServiceCreator]

        (auditSinkServiceCreator.dataStream _)
          .expects(dataStreamSinkConfig1.auditCluster)
          .once()
          .returns(mockedDataStreamAuditSinkService(dataStreamService1))

        (auditSinkServiceCreator.dataStream _)
          .expects(dataStreamSinkConfig2.auditCluster)
          .once()
          .returns(mockedDataStreamAuditSinkService(dataStreamService2))

        implicit val systemContext: SystemContext = createSystemContext()
        val readonlyRest = readonlyRestBoot(coreFactory, mock[IndexDocumentManager], auditSinkServiceCreator)
        val esConfigBasedRorSettings = forceCreateEsConfigBasedRorSettings("/boot_tests/forced_file_loading_with_audit/")

        val result = readonlyRest.start(esConfigBasedRorSettings).runSyncUnsafe()
        inside(result) {
          case Left(StartingFailure(message, _)) =>
            val expectedMessage =
              s"""Errors:
                 |Unable to configure audit output using a data stream in local cluster. Details: [Failed to setup ROR audit data stream readonlyrest_audit. Reason: Unable to determine if the index lifecycle policy with ID 'readonlyrest_audit-lifecycle-policy' has been created]
                 |Unable to configure audit output using a data stream in remote cluster 0.0.0.0. Details: [Failed to setup ROR audit data stream readonlyrest_audit. Reason: Unable to determine if component template with ID 'readonlyrest_audit-mappings' has been created]""".stripMarginAndReplaceWindowsLineBreak
            message should be(expectedMessage)
        }
      }
    }
  }

  private def forceCreateEsConfigBasedRorSettings(resourceEsConfigDir: String)
                                                 (implicit systemContext: SystemContext) = {
    createEsConfigBasedRorSettings(resourceEsConfigDir) match {
      case Right(settings) => settings
      case Left(error) => throw new IllegalStateException(s"Cannot create EsConfigBasedRorSettings: $error")
    }
  }

  private def createEsConfigBasedRorSettings(resourceEsConfigDir: String)
                                            (implicit systemContext: SystemContext): Either[LoadingError, EsConfigBasedRorSettings] = {
    val esConfig = File(getResourcePath(resourceEsConfigDir))
    val esEnv = EsEnv(esConfig, esConfig, defaultEsVersionForTests, testEsNodeSettings)
    EsConfigBasedRorSettings
      .from(esEnv)
      .runSyncUnsafe()
  }

  private def createSystemContext(refreshInterval: Option[FiniteDuration] = None,
                                  maxYamlSize: Option[String] = None): SystemContext = {
    def mapWithIntervalFrom(refreshInterval: Option[FiniteDuration]) =
      refreshInterval
        .map(i => "com.readonlyrest.settings.refresh.interval" -> i.toSeconds.toString)
        .toMap

    def mapWithMaxYamlSize(maxYamlSize: Option[String]) =
      maxYamlSize
        .map(size => "com.readonlyrest.settings.maxSize" -> size)
        .toMap

    new SystemContext(propertiesProvider =
      TestsPropertiesProvider.usingMap(
        mapWithIntervalFrom(refreshInterval) ++
          mapWithMaxYamlSize(maxYamlSize) ++
          Map(
            "com.readonlyrest.settings.loading.delay" -> "1",
            "com.readonlyrest.settings.loading.attempts.count" -> "1"
          )
      )
    )
  }

  private def readonlyRestBoot(factory: CoreFactory,
                               indexDocumentManager: IndexDocumentManager,
                               auditSinkServiceCreator: AuditSinkServiceCreator = mock[AuditSinkServiceCreator])
                              (implicit systemContext: SystemContext): ReadonlyRest = {
    ReadonlyRest.create(factory, indexDocumentManager, auditSinkServiceCreator)
  }

  private def mockCoreFactory(mockedCoreFactory: CoreFactory,
                              loadedMainSettingsResourceFileName: String,
                              accessControlMock: AccessControlList = mockEnabledAccessControl,
                              dependencies: RorDependencies = RorDependencies.noOp,
                              auditingSettings: Option[AuditingTool.AuditSettings] = None): CoreFactory = {
    mockCoreFactory(
      mockedCoreFactory,
      rorSettingsFromResource(loadedMainSettingsResourceFileName),
      accessControlMock,
      dependencies,
      auditingSettings
    )
  }

  private def mockCoreFactory(mockedCoreFactory: CoreFactory,
                              loadedMainSettings: RawRorSettings,
                              accessControlMock: AccessControlList,
                              dependencies: RorDependencies,
                              auditingSettings: Option[AuditingTool.AuditSettings]): CoreFactory = {
    (mockedCoreFactory.createCoreFrom _)
      .expects(where {
        (settings: RawRorSettings, _, _, _, _) => settings == loadedMainSettings
      })
      .once()
      .returns(Task.now(Right(Core(accessControlMock, dependencies, auditingSettings))))
    mockedCoreFactory
  }

  private def mockCoreFactory(mockedCoreFactory: CoreFactory,
                              resourceFileName: String,
                              createCoreResult: Task[Either[NonEmptyList[CoreCreationError], Core]]): CoreFactory = {
    (mockedCoreFactory.createCoreFrom _)
      .expects(where {
        (settings: RawRorSettings, _, _, _, _) => settings == rorSettingsFromResource(resourceFileName)
      })
      .once()
      .returns(createCoreResult)
    mockedCoreFactory
  }

  private def mockFailedCoreFactory(mockedCoreFactory: CoreFactory,
                                    resourceFileName: String): CoreFactory = {
    mockFailedCoreFactory(mockedCoreFactory, rorSettingsFromResource(resourceFileName))
  }

  private def mockFailedCoreFactory(mockedCoreFactory: CoreFactory,
                                    rawRorSettings: RawRorSettings): CoreFactory = {
    (mockedCoreFactory.createCoreFrom _)
      .expects(where {
        (settings: RawRorSettings, _, _, _, _) => settings == rawRorSettings
      })
      .once()
      .returns(Task.now(Left(NonEmptyList.one(CoreCreationError.GeneralReadonlyrestSettingsError(Message("failed"))))))
    mockedCoreFactory
  }

  private def mockEnabledAccessControl = {
    val mockedAccessControl = mock[EnabledAcl]
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

  private def mockDisabledAccessControl = {
    val mockedAccessControl = mock[DisabledAcl]
    (() => mockedAccessControl.staticContext)
      .expects()
      .anyNumberOfTimes()
      .returns(mockAccessControlStaticContext)
    (() => mockedAccessControl.description)
      .expects()
      .anyNumberOfTimes()
      .returns("DISABLED")
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

  private lazy val testSettings1 = rorSettingsFromUnsafe(
    """
      |readonlyrest:
      |  access_control_rules:
      |
      |  - name: test_block
      |    type: allow
      |    auth_key: admin:container
      |
      |""".stripMargin
  )

  private lazy val testSettings2 = rorSettingsFromUnsafe(
    """
      |readonlyrest:
      |  access_control_rules:
      |
      |  - name: test_block_updated
      |    type: allow
      |    auth_key: admin:container
      |
      |""".stripMargin
  )

  private lazy val testSettingsMalformed = rorSettingsFromUnsafe(
    """
      |readonlyrest:
      |  access_control_rules:
      |
      |  - name: test_block_updated
      |    type: allow
      |
      |""".stripMargin
  )

  private lazy val notConfiguredAuthServicesMocksJson =
    s"""
       |{
       |  "ldapMocks": {},
       |  "externalAuthenticationMocks": {},
       |  "externalAuthorizationMocks": {}
       |}
       |""".stripMargin

  private lazy val configuredAuthServicesMocksJson =
    s"""
       |{
       |  "ldapMocks": {
       |    "ldap1": {
       |      "users": [
       |        {
       |          "id": "Tom",
       |          "groups": ["group1", "group2"]
       |        }
       |      ]
       |    }
       |  },
       |  "externalAuthenticationMocks": {
       |    "ext1": {
       |      "users": [
       |        {
       |          "id": "Matt"
       |        },
       |        {
       |          "id": "Emily"
       |        }
       |      ]
       |    }
       |  },
       |  "externalAuthorizationMocks": {
       |    "grp1": {
       |      "users": [
       |        {
       |          "id": "Bruce",
       |          "groups": ["group3", "group4"]
       |        }
       |      ]
       |    }
       |  }
       |}
       |""".stripMargin

  private def newRequestId() = RequestId(UUID.randomUUID().toString)

  private abstract class EnabledAcl extends AccessControlList

  private abstract class DisabledAcl extends AccessControlList

  private def mockedDataStreamAuditSinkService(dataStreamService: DataStreamService) = {
    val dataStreamAuditSink = mock[DataStreamBasedAuditSinkService]
    (() => dataStreamAuditSink.dataStreamCreator)
      .expects()
      .once()
      .returns(new AuditDataStreamCreator(NonEmptyList.of(dataStreamService)))
    dataStreamAuditSink
  }

  private def mockGettingMainSettingsReturnsError(mockedManager: IndexDocumentManager,
                                                  error: ReadError,
                                                  attemptCount: AttemptCount = AttemptCount.Exact(1)) = {
    mockGettingSettings(
      mockedManager = mockedManager,
      expectedIndex = ".readonlyrest",
      expectedDocument = "1",
      returnsResponse = Task.now(Left(error)),
      attemptCount = attemptCount
    )
  }

  private def mockGettingMainSettings(mockedManager: IndexDocumentManager,
                                      returnedMainSettingsResourceFileName: String,
                                      attemptCount: AttemptCount = AttemptCount.Exact(1)) = {
    mockGettingSettings(
      mockedManager = mockedManager,
      expectedIndex = ".readonlyrest",
      expectedDocument = "1",
      returnsResponse = Task.now(Right(
        circeJsonFrom(s"""{ "settings": "${escapeJava(getResourceContent(returnedMainSettingsResourceFileName))}"}""")
      )),
      attemptCount = attemptCount
    )
  }

  private def mockGettingTestSettings(mockedManager: IndexDocumentManager,
                                      testSettingsJson: Json,
                                      attemptCount: AttemptCount = AttemptCount.Exact(1)) = {
    mockGettingSettings(
      mockedManager = mockedManager,
      expectedIndex = ".readonlyrest",
      expectedDocument = "2",
      returnsResponse = Task.now(Right(testSettingsJson)),
      attemptCount = attemptCount
    )
  }

  private def mockGettingTestSettingsReturnsError(mockedManager: IndexDocumentManager,
                                                  error: ReadError,
                                                  attemptCount: AttemptCount = AttemptCount.Exact(1)) = {
    mockGettingSettings(
      mockedManager = mockedManager,
      expectedIndex = ".readonlyrest",
      expectedDocument = "2",
      returnsResponse = Task.now(Left(error)),
      attemptCount = attemptCount
    )
  }

  private def mockSavingMainSettings(mockedManager: IndexDocumentManager,
                                     resourceFileName: String,
                                     saveResult: Task[Either[WriteError, Unit]] = Task.now(Right(()))) = {
    (mockedManager.saveDocumentJson _)
      .expects(
        fullIndexName(".readonlyrest"),
        "1",
        circeJsonFrom(s"""{ "settings": "${escapeJava(getResourceContent(resourceFileName))}"}""")
      )
      .once()
      .returns(saveResult)
    mockedManager
  }

  private def mockGettingSettings(mockedManager: IndexDocumentManager,
                                  expectedIndex: String,
                                  expectedDocument: String,
                                  returnsResponse: Task[Either[ReadError, Json]],
                                  attemptCount: AttemptCount) = {
    val handler = (mockedManager.documentAsJson _)
      .expects(fullIndexName(expectedIndex), expectedDocument)
    val handlerWithRepetitions = attemptCount match {
      case AttemptCount.AnyNumberOfTimes => handler.anyNumberOfTimes()
      case AttemptCount.Exact(count) => handler.repeated(count)
    }
    handlerWithRepetitions
      .returns(returnsResponse)
    mockedManager
  }

  private sealed trait AttemptCount
  private object AttemptCount {
    case object AnyNumberOfTimes extends AttemptCount
    final case class Exact(count: Int) extends AttemptCount
  }

  // anonymous class instead of mock due to final defs and protected methods in DataStreamService
  private def mockedDataSteamService(dataStreamExists: Boolean,
                                     ilmExists: Boolean = false,
                                     ilmCreationResult: CreationResult = CreationResult.Acknowledged,
                                     componentTemplateExists: Boolean = false,
                                     componentTemplateResult: CreationResult = CreationResult.Acknowledged,
                                     indexTemplateExists: Boolean = false,
                                     indexTemplateResult: CreationResult = CreationResult.Acknowledged,
                                     dataStreamResult: CreationResult = CreationResult.Acknowledged) = new DataStreamService {
    override def checkDataStreamExists(dataStreamName: DataStreamName.Full): Task[Boolean] = Task.now(dataStreamExists)

    override protected def checkIndexLifecyclePolicyExists(policyId: NonEmptyString): Task[Boolean] = Task.pure(ilmExists)

    override protected def createIndexLifecyclePolicy(policy: DataStreamSettings.LifecyclePolicy): Task[CreationResult] = Task.now(ilmCreationResult)

    override protected def checkComponentTemplateExists(templateName: TemplateName): Task[Boolean] = Task.pure(componentTemplateExists)

    override protected def createComponentTemplateForMappings(settings: DataStreamSettings.ComponentTemplateMappings): Task[CreationResult] = Task.now(componentTemplateResult)

    override protected def createComponentTemplateForIndex(settings: DataStreamSettings.ComponentTemplateSettings): Task[CreationResult] = Task.now(componentTemplateResult)

    override protected def checkIndexTemplateExists(templateName: TemplateName): Task[Boolean] = Task.pure(indexTemplateExists)

    override protected def createIndexTemplate(settings: DataStreamSettings.IndexTemplateSettings): Task[CreationResult] = Task.now(indexTemplateResult)

    override protected def createDataStream(dataStreamName: DataStreamName.Full): Task[CreationResult] = Task.now(dataStreamResult)
  }
}
