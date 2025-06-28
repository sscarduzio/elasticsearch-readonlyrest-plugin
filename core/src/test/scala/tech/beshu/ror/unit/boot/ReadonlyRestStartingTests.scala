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

import cats.data.NonEmptyList
import cats.effect.Resource
import cats.implicits.*
import eu.timepit.refined.types.string.NonEmptyString
import io.lemonlabs.uri.Uri
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{EitherValues, Inside, OptionValues}
import tech.beshu.ror.accesscontrol.AccessControlList
import tech.beshu.ror.accesscontrol.AccessControlList.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.audit.AuditingTool
import tech.beshu.ror.accesscontrol.audit.AuditingTool.AuditSettings.AuditSink
import tech.beshu.ror.accesscontrol.audit.sink.{AuditDataStreamCreator, AuditSinkServiceCreator, DataStreamAndIndexBasedAuditSinkServiceCreator}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService
import tech.beshu.ror.accesscontrol.blocks.definitions.{ExternalAuthenticationService, ExternalAuthorizationService}
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider.{ExternalAuthenticationServiceMock, ExternalAuthorizationServiceMock, LdapServiceMock}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.{Core, CoreFactory}
import tech.beshu.ror.accesscontrol.logging.AccessControlListLoggingDecorator
import tech.beshu.ror.boot.ReadonlyRest.StartingFailure
import tech.beshu.ror.boot.RorInstance.{IndexConfigInvalidationError, TestConfig}
import tech.beshu.ror.boot.{ReadonlyRest, RorInstance}
import tech.beshu.ror.configuration.RorConfig.NoOpImpersonationWarningsReader
import tech.beshu.ror.configuration.index.SavingIndexConfigError
import tech.beshu.ror.configuration.{EnvironmentConfig, RawRorConfig, RorConfig}
import tech.beshu.ror.es.DataStreamService.CreationResult.{Acknowledged, NotAcknowledged}
import tech.beshu.ror.es.DataStreamService.{CreationResult, DataStreamSettings}
import tech.beshu.ror.es.IndexJsonContentService.{CannotReachContentSource, CannotWriteToIndex, ContentNotFound, WriteError}
import tech.beshu.ror.es.{DataStreamBasedAuditSinkService, DataStreamService, EsEnv, IndexJsonContentService}
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.DurationOps.*
import tech.beshu.ror.utils.TestsPropertiesProvider
import tech.beshu.ror.utils.TestsUtils.*

import java.time.Clock
import java.util.UUID
import scala.concurrent.duration.*
import scala.language.postfixOps

class ReadonlyRestStartingTests
  extends AnyWordSpec
    with Inside with OptionValues with EitherValues
    with MockFactory with Eventually {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(15, Seconds)), interval = scaled(Span(100, Millis)))

  private implicit val testClock: Clock = Clock.systemUTC()

  "A ReadonlyREST core" should {
    "support the main engine" should {
      "be loaded from file" when {
        "index is not available but file config is provided" in withReadonlyRest({
          val mockedIndexJsonContentManager = mock[IndexJsonContentService]
          (mockedIndexJsonContentManager.sourceOf _)
            .expects(fullIndexName(".readonlyrest"), "1")
            .repeated(1)
            .returns(Task.now(Left(CannotReachContentSource)))

          mockIndexJsonContentManagerSourceOfCallTestConfig(mockedIndexJsonContentManager)

          val coreFactory = mockCoreFactory(mock[CoreFactory], "/boot_tests/no_index_config_file_config_provided/readonlyrest.yml")
          readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, "/boot_tests/no_index_config_file_config_provided/")
        }) { rorInstance =>
          val acl = rorInstance.engines.value.mainEngine.core.accessControl
          acl shouldBe a[AccessControlListLoggingDecorator]
          acl.asInstanceOf[AccessControlListLoggingDecorator].underlying shouldBe a[EnabledAcl]
        }
        "file loading is forced in elasticsearch.yml" in withReadonlyRest({
          val mockedIndexJsonContentManager = mock[IndexJsonContentService]
          mockIndexJsonContentManagerSourceOfCallTestConfig(mockedIndexJsonContentManager)

          val coreFactory = mockCoreFactory(mock[CoreFactory], "/boot_tests/forced_file_loading/readonlyrest.yml")
          readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, "/boot_tests/forced_file_loading/")
        }) { rorInstance =>
          val acl = rorInstance.engines.value.mainEngine.core.accessControl
          acl shouldBe a[AccessControlListLoggingDecorator]
          acl.asInstanceOf[AccessControlListLoggingDecorator].underlying shouldBe a[EnabledAcl]
        }
      }
      "be loaded from index" when {
        "index is available and file config is provided" in withReadonlyRest({
          val resourcesPath = "/boot_tests/index_config_available_file_config_provided/"
          val indexConfigFile = "readonlyrest_index.yml"

          val mockedIndexJsonContentManager = mock[IndexJsonContentService]
          mockIndexJsonContentManagerSourceOfCall(mockedIndexJsonContentManager, resourcesPath + indexConfigFile)
          mockIndexJsonContentManagerSourceOfCallTestConfig(mockedIndexJsonContentManager)

          val coreFactory = mockCoreFactory(mock[CoreFactory], resourcesPath + indexConfigFile)
          readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath)
        }) { rorInstance =>
          val acl = rorInstance.engines.value.mainEngine.core.accessControl
          acl shouldBe a[AccessControlListLoggingDecorator]
          acl.asInstanceOf[AccessControlListLoggingDecorator].underlying shouldBe a[EnabledAcl]
        }
        "index is available and file config is not provided" in withReadonlyRest({
          val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
          val indexConfigFile = "readonlyrest_index.yml"

          val mockedIndexJsonContentManager = mock[IndexJsonContentService]
          mockIndexJsonContentManagerSourceOfCall(mockedIndexJsonContentManager, resourcesPath + indexConfigFile)
          mockIndexJsonContentManagerSourceOfCallTestConfig(mockedIndexJsonContentManager)

          val coreFactory = mockCoreFactory(mock[CoreFactory], resourcesPath + indexConfigFile)

          readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath)
        }) { rorInstance =>
          val acl = rorInstance.engines.value.mainEngine.core.accessControl
          acl shouldBe a[AccessControlListLoggingDecorator]
          acl.asInstanceOf[AccessControlListLoggingDecorator].underlying shouldBe a[EnabledAcl]
        }
      }
      "be able to be reloaded" when {
        "new config is different than old one" in withReadonlyRest({
          val resourcesPath = "/boot_tests/config_reloading/"
          val initialIndexConfigFile = "readonlyrest_initial.yml"
          val newIndexConfigFile = "readonlyrest_first.yml"

          val mockedIndexJsonContentManager = mock[IndexJsonContentService]
          mockIndexJsonContentManagerSourceOfCall(mockedIndexJsonContentManager, resourcesPath + initialIndexConfigFile)

          val coreFactory = mock[CoreFactory]
          mockCoreFactory(coreFactory, resourcesPath + initialIndexConfigFile)
          mockCoreFactory(coreFactory, resourcesPath + newIndexConfigFile)
          mockIndexJsonContentManagerSaveCall(mockedIndexJsonContentManager, resourcesPath + newIndexConfigFile)
          mockIndexJsonContentManagerSourceOfCallTestConfig(mockedIndexJsonContentManager)

          readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(0 seconds))
        }) { rorInstance =>
          val mainEngine = rorInstance.engines.value.mainEngine
          mainEngine.core.accessControl shouldBe a[AccessControlListLoggingDecorator]
          mainEngine.core.accessControl.asInstanceOf[AccessControlListLoggingDecorator].underlying shouldBe a[EnabledAcl]

          val reload1Result = rorInstance
            .forceReloadAndSave(rorConfigFromResource("/boot_tests/config_reloading/readonlyrest_first.yml"))(newRequestId())
            .runSyncUnsafe()

          reload1Result should be(Right(()))
          assert(mainEngine != rorInstance.engines.value.mainEngine, "Engine was not reloaded")
        }
        "two parallel force reloads are invoked" in withReadonlyRestExt({
          val resourcesPath = "/boot_tests/config_reloading/"
          val initialIndexConfigFile = "readonlyrest_initial.yml"
          val firstNewIndexConfigFile = "readonlyrest_first.yml"
          val secondNewIndexConfigFile = "readonlyrest_second.yml"

          val mockedIndexJsonContentManager = mock[IndexJsonContentService]
          mockIndexJsonContentManagerSourceOfCall(mockedIndexJsonContentManager, resourcesPath + initialIndexConfigFile)
          mockIndexJsonContentManagerSourceOfCallTestConfig(mockedIndexJsonContentManager)

          val coreFactory = mock[CoreFactory]
          mockCoreFactory(coreFactory, resourcesPath + initialIndexConfigFile)
          mockCoreFactory(coreFactory, resourcesPath + firstNewIndexConfigFile)
          mockCoreFactory(coreFactory, resourcesPath + secondNewIndexConfigFile,
            createCoreResult =
              Task
                .sleep(100 millis)
                .map(_ => Right(Core(mockEnabledAccessControl, RorConfig.disabled))) // very long creation
          )
          mockIndexJsonContentManagerSaveCall(
            mockedIndexJsonContentManager,
            resourcesPath + firstNewIndexConfigFile,
            Task.sleep(500 millis).map(_ => Right(())) // very long saving
          )

          val readonlyrest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(0 seconds))
          (readonlyrest, mockedIndexJsonContentManager)
        }) { case (rorInstance, mockedIndexJsonContentManager) =>
          val resourcesPath = "/boot_tests/config_reloading/"
          val firstNewIndexConfigFile = "readonlyrest_first.yml"
          val secondNewIndexConfigFile = "readonlyrest_second.yml"

          eventually {
            rorInstance.engines.value.mainEngine.core.accessControl
          }

          val results = Task
            .parSequence(List(
              rorInstance
                .forceReloadAndSave(rorConfigFromResource(resourcesPath + firstNewIndexConfigFile))(newRequestId())
                .map { result =>
                  // schedule after first finish
                  mockIndexJsonContentManagerSaveCall(mockedIndexJsonContentManager, resourcesPath + secondNewIndexConfigFile)
                  result
                },
              Task
                .sleep(200 millis)
                .flatMap { _ =>
                  rorInstance.forceReloadAndSave(rorConfigFromResource(resourcesPath + secondNewIndexConfigFile))(newRequestId())
                }
            ))
            .runSyncUnsafe()
            .sequence

          results should be(Right(List((), ())))
        }
      }
      "be reloaded if index config changes" in withReadonlyRest({
        val resourcesPath = "/boot_tests/index_config_reloading/"
        val originIndexConfigFile = "readonlyrest.yml"
        val updatedIndexConfigFile = "updated_readonlyrest.yml"

        val mockedIndexJsonContentManager = mock[IndexJsonContentService]
        val coreFactory = mock[CoreFactory]

        mockIndexJsonContentManagerSourceOfCall(mockedIndexJsonContentManager, resourcesPath + originIndexConfigFile, repeatedCount = 1)
        mockIndexJsonContentManagerSourceOfCallTestConfig(mockedIndexJsonContentManager)
        mockCoreFactory(coreFactory, resourcesPath + originIndexConfigFile, mockDisabledAccessControl)

        mockIndexJsonContentManagerSourceOfCall(mockedIndexJsonContentManager, resourcesPath + updatedIndexConfigFile)
        mockCoreFactory(coreFactory, resourcesPath + updatedIndexConfigFile)

        readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(2 seconds))
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
        "force load from file is set and config is malformed" in {
          val readonlyRest = readonlyRestBoot(mock[CoreFactory], mock[IndexJsonContentService], "/boot_tests/forced_file_loading_malformed_config/")

          val result = readonlyRest.start().runSyncUnsafe()

          inside(result) { case Left(failure) =>
            failure.message should startWith("Settings file is malformed:")
          }
        }
        "force load from file is set and config cannot be loaded" in {
          val coreFactory = mockFailedCoreFactory(mock[CoreFactory], "/boot_tests/forced_file_loading_bad_config/readonlyrest.yml")
          val readonlyRest = readonlyRestBoot(
            factory = coreFactory,
            indexJsonContentService = mockIndexJsonContentManagerSourceOfCallTestConfig(mock[IndexJsonContentService]),
            configPath = "/boot_tests/forced_file_loading_bad_config/"
          )

          val result = readonlyRest.start().runSyncUnsafe()

          inside(result) { case Left(failure) =>
            failure.message shouldBe "Errors:\nfailed"
          }
        }
        "index config doesn't exist and file config is malformed" in {
          val mockedIndexJsonContentManager = mock[IndexJsonContentService]
          (mockedIndexJsonContentManager.sourceOf _)
            .expects(fullIndexName(".readonlyrest"), "1")
            .repeated(1)
            .returns(Task.now(Left(ContentNotFound)))

          val readonlyRest = readonlyRestBoot(mock[CoreFactory], mockedIndexJsonContentManager, "/boot_tests/index_config_not_exists_malformed_file_config/")

          val result = readonlyRest.start().runSyncUnsafe()

          inside(result) { case Left(failure) =>
            failure.message should startWith("Settings content is malformed.")
          }
        }
        "index config doesn't exist and file config cannot be loaded" in {
          val mockedIndexJsonContentManager = mock[IndexJsonContentService]
          (mockedIndexJsonContentManager.sourceOf _)
            .expects(fullIndexName(".readonlyrest"), "1")
            .repeated(1)
            .returns(Task.now(Left(ContentNotFound)))
          mockIndexJsonContentManagerSourceOfCallTestConfig(mockedIndexJsonContentManager)

          val coreFactory = mockFailedCoreFactory(mock[CoreFactory], "/boot_tests/index_config_not_exists_bad_file_config/readonlyrest.yml")
          val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, "/boot_tests/index_config_not_exists_bad_file_config/")

          val result = readonlyRest.start().runSyncUnsafe()

          inside(result) { case Left(failure) =>
            failure.message shouldBe "Errors:\nfailed"
          }
        }
        "index config is malformed" in {
          val resourcesPath = "/boot_tests/malformed_index_config/"
          val indexConfigFile = "readonlyrest_index.yml"

          val mockedIndexJsonContentManager = mock[IndexJsonContentService]
          mockIndexJsonContentManagerSourceOfCall(mockedIndexJsonContentManager, resourcesPath + indexConfigFile)
          mockIndexJsonContentManagerSourceOfCallTestConfig(mockedIndexJsonContentManager)

          val readonlyRest = readonlyRestBoot(mock[CoreFactory], mockedIndexJsonContentManager, resourcesPath)

          val result = readonlyRest.start().runSyncUnsafe()

          inside(result) { case Left(failure) =>
            failure.message should startWith("Settings content is malformed.")
          }
        }
        "index config cannot be loaded" in {
          val resourcesPath = "/boot_tests/bad_index_config/"
          val indexConfigFile = "readonlyrest_index.yml"

          val mockedIndexJsonContentManager = mock[IndexJsonContentService]
          mockIndexJsonContentManagerSourceOfCall(mockedIndexJsonContentManager, resourcesPath + indexConfigFile)
          mockIndexJsonContentManagerSourceOfCallTestConfig(mockedIndexJsonContentManager)

          val coreFactory = mockFailedCoreFactory(mock[CoreFactory], resourcesPath + indexConfigFile)
          val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath)

          val result = readonlyRest.start().runSyncUnsafe()

          inside(result) { case Left(failure) =>
            failure.message shouldBe "Errors:\nfailed"
          }
        }
        "ROR SSL (in elasticsearch.yml) is tried to be used when XPack Security is enabled" in {
          val readonlyRest = readonlyRestBoot(mock[CoreFactory], mock[IndexJsonContentService], "/boot_tests/ror_ssl_declared_in_es_file_xpack_security_enabled/")

          val result = readonlyRest.start().runSyncUnsafe()

          inside(result) { case Left(failure) =>
            failure.message should be("Cannot use ROR SSL configuration when XPack Security is enabled")
          }
        }
        "ROR SSL (in readonlyrest.yml) is tried to be used when XPack Security is enabled" in {
          val readonlyRest = readonlyRestBoot(mock[CoreFactory], mock[IndexJsonContentService], "/boot_tests/ror_ssl_declared_in_readonlyrest_file_xpack_security_enabled/")

          val result = readonlyRest.start().runSyncUnsafe()

          inside(result) { case Left(failure) =>
            failure.message should be("Cannot use ROR SSL configuration when XPack Security is enabled")
          }
        }
        "ROR FIPS SSL is tried to be used when XPack Security is enabled" in {
          val readonlyRest = readonlyRestBoot(mock[CoreFactory], mock[IndexJsonContentService], "/boot_tests/ror_fisb_ssl_declared_in_readonlyrest_file_xpack_security_enabled/")

          val result = readonlyRest.start().runSyncUnsafe()

          inside(result) { case Left(failure) =>
            failure.message should be("Cannot use ROR FIBS configuration when XPack Security is enabled")
          }
        }
      }
    }
    "support the test engine" which {
      "can be initialized" when {
        "there is no config in index" in withReadonlyRest({
          val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
          val indexConfigFile = "readonlyrest_index.yml"

          val mockedIndexJsonContentManager = mock[IndexJsonContentService]
          mockIndexJsonContentManagerSourceOfCall(mockedIndexJsonContentManager, resourcesPath + indexConfigFile)

          (mockedIndexJsonContentManager.sourceOf _)
            .expects(fullIndexName(".readonlyrest"), "2")
            .repeated(1)
            .returns(Task.now(Left(ContentNotFound)))

          val coreFactory = mock[CoreFactory]
          mockCoreFactory(coreFactory, resourcesPath + indexConfigFile)

          readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(5 seconds))
        }) { rorInstance =>
          rorInstance.engines.value.impersonatorsEngine should be(Option.empty)

          rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() should be(TestConfig.NotSet)
        }
        "there is some config stored in index" should {
          "load test engine as active" when {
            "config is still valid" in withReadonlyRestExt({
              val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
              val indexConfigFile = "readonlyrest_index.yml"

              val mockedIndexJsonContentManager = mock[IndexJsonContentService]
              mockIndexJsonContentManagerSourceOfCall(mockedIndexJsonContentManager, resourcesPath + indexConfigFile)

              lazy val expirationTimestamp = testClock.instant().plusSeconds(100)
              (mockedIndexJsonContentManager.sourceOf _)
                .expects(fullIndexName(".readonlyrest"), "2")
                .repeated(1)
                .returns(Task.now(Right(
                  Map(
                    "settings" -> testConfig1.raw,
                    "expiration_ttl_millis" -> "100000",
                    "expiration_timestamp" -> expirationTimestamp.toString,
                    "auth_services_mocks" -> configuredAuthServicesMocksJson,
                  )
                )))

              val coreFactory = mock[CoreFactory]
              mockCoreFactory(coreFactory, resourcesPath + indexConfigFile)
              mockCoreFactory(coreFactory, testConfig1, mockEnabledAccessControl, disabledRorConfig)

              val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(10 seconds))
              (readonlyRest, expirationTimestamp)
            }) { case (rorInstance, expirationTimestamp) =>
              rorInstance.engines.value.impersonatorsEngine.value.core.accessControl shouldBe a[AccessControlListLoggingDecorator]

              rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() should be(
                TestConfig.Present(
                  config = RorConfig.disabled,
                  rawConfig = testConfig1,
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
              val indexConfigFile = "readonlyrest_index.yml"

              val mockedIndexJsonContentManager = mock[IndexJsonContentService]
              mockIndexJsonContentManagerSourceOfCall(mockedIndexJsonContentManager, resourcesPath + indexConfigFile)

              lazy val expirationTimestamp = testClock.instant().minusSeconds(100)
              (mockedIndexJsonContentManager.sourceOf _)
                .expects(fullIndexName(".readonlyrest"), "2")
                .repeated(1)
                .returns(Task.now(Right(
                  Map(
                    "settings" -> testConfig1.raw,
                    "expiration_ttl_millis" -> "100000",
                    "expiration_timestamp" -> expirationTimestamp.toString,
                    "auth_services_mocks" -> notConfiguredAuthServicesMocksJson,
                  )
                )))

              val coreFactory = mock[CoreFactory]
              mockCoreFactory(coreFactory, resourcesPath + indexConfigFile)

              readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(10 seconds))
            }) { rorInstance =>
              rorInstance.engines.value.impersonatorsEngine should be(Option.empty)

              rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() should be(
                TestConfig.Invalidated(
                  recent = testConfig1,
                  configuredTtl = (100 seconds).toRefinedPositiveUnsafe
                )
              )
            }
          }
        }
        "index is not accessible" should {
          "fallback to not configured" in withReadonlyRest({
            val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
            val indexConfigFile = "readonlyrest_index.yml"

            val mockedIndexJsonContentManager = mock[IndexJsonContentService]
            mockIndexJsonContentManagerSourceOfCall(mockedIndexJsonContentManager, resourcesPath + indexConfigFile)

            (mockedIndexJsonContentManager.sourceOf _)
              .expects(fullIndexName(".readonlyrest"), "2")
              .repeated(1)
              .returns(Task.now(Left(CannotReachContentSource)))

            val coreFactory = mock[CoreFactory]
            mockCoreFactory(coreFactory, resourcesPath + indexConfigFile)

            readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(5 seconds))
          }) { rorInstance =>
            rorInstance.engines.value.impersonatorsEngine should be(Option.empty)

            rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() should be(TestConfig.NotSet)
          }
        }
        "settings structure is not valid" should {
          "fallback to not configured" in withReadonlyRest({
            val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
            val indexConfigFile = "readonlyrest_index.yml"

            val mockedIndexJsonContentManager = mock[IndexJsonContentService]
            mockIndexJsonContentManagerSourceOfCall(mockedIndexJsonContentManager, resourcesPath + indexConfigFile)

            lazy val expirationTimestamp = testClock.instant().minusSeconds(100)
            (mockedIndexJsonContentManager.sourceOf _)
              .expects(fullIndexName(".readonlyrest"), "2")
              .repeated(1)
              .returns(Task.now(Right(
                Map(
                  "settings" -> "malformed_config", // malformed ror config
                  "expiration_ttl_millis" -> "100000",
                  "expiration_timestamp" -> expirationTimestamp.toString,
                  "auth_services_mocks" -> notConfiguredAuthServicesMocksJson,
                )
              )))

            val coreFactory = mock[CoreFactory]
            mockCoreFactory(coreFactory, resourcesPath + indexConfigFile)

            readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(5 seconds))
          }) { rorInstance =>
            rorInstance.engines.value.impersonatorsEngine should be(Option.empty)

            rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() should be(TestConfig.NotSet)
          }
        }
        "settings structure is valid, rule is malformed and cannot start engine" should {
          "fallback to invalidated config" in withReadonlyRest({
            val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
            val indexConfigFile = "readonlyrest_index.yml"

            val mockedIndexJsonContentManager = mock[IndexJsonContentService]
            mockIndexJsonContentManagerSourceOfCall(mockedIndexJsonContentManager, resourcesPath + indexConfigFile)

            (mockedIndexJsonContentManager.sourceOf _)
              .expects(fullIndexName(".readonlyrest"), "2")
              .repeated(1)
              .returns(Task.now(Right(
                Map(
                  "settings" -> testConfigMalformed.raw,
                  "expiration_ttl_millis" -> "100000",
                  "expiration_timestamp" -> testClock.instant().plusSeconds(100).toString,
                  "auth_services_mocks" -> notConfiguredAuthServicesMocksJson,
                )
              )))

            val coreFactory = mock[CoreFactory]
            mockCoreFactory(coreFactory, resourcesPath + indexConfigFile)
            mockFailedCoreFactory(coreFactory, testConfigMalformed)

            readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(5 seconds))
          }) { rorInstance =>
            rorInstance.engines.value.impersonatorsEngine should be(Option.empty)
            rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() should be(
              TestConfig.Invalidated(
                recent = testConfigMalformed,
                configuredTtl = (100 seconds).toRefinedPositiveUnsafe
              )
            )
          }
        }
      }
      "can be loaded on demand" when {
        "there is no previous engine" in withReadonlyRestExt({
          val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
          val indexConfigFile = "readonlyrest_index.yml"

          val mockedIndexJsonContentManager = mock[IndexJsonContentService]
          mockIndexJsonContentManagerSourceOfCall(mockedIndexJsonContentManager, resourcesPath + indexConfigFile)

          (mockedIndexJsonContentManager.sourceOf _)
            .expects(fullIndexName(".readonlyrest"), "2")
            .repeated(1)
            .returns(Task.now(Left(ContentNotFound)))

          val coreFactory = mock[CoreFactory]
          mockCoreFactory(coreFactory, resourcesPath + indexConfigFile)
          mockCoreFactory(coreFactory, testConfig1, mockEnabledAccessControl, disabledRorConfig)

          val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(10 seconds))
          (readonlyRest, mockedIndexJsonContentManager)
        }) { case (rorInstance, mockedIndexJsonContentManager) =>
          rorInstance.engines.value.impersonatorsEngine should be(Option.empty)

          rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() should be(TestConfig.NotSet)

          (mockedIndexJsonContentManager.saveContent _)
            .expects(
              where {
                (config: IndexName.Full, id: String, content: Map[String, String]) =>
                  config == fullIndexName(".readonlyrest") &&
                    id == "2" &&
                    content.get("settings").contains(testConfig1.raw) &&
                    content.get("expiration_ttl_millis").contains("60000") &&
                    content.contains("expiration_timestamp") &&
                    content.contains("auth_services_mocks")
              }
            )
            .repeated(1)
            .returns(Task.now(Right(())))

          val testEngineReloadResult = rorInstance
            .forceReloadTestConfigEngine(testConfig1, (1 minute).toRefinedPositiveUnsafe)(newRequestId())
            .runSyncUnsafe()

          testEngineReloadResult.value shouldBe a[TestConfig.Present]
          rorInstance.engines.value.impersonatorsEngine.value.core.accessControl shouldBe a[AccessControlListLoggingDecorator]

          val testEngineConfig = rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe()
          testEngineConfig shouldBe a[TestConfig.Present]
          Option(testEngineConfig.asInstanceOf[TestConfig.Present]).map(i => (i.rawConfig, i.configuredTtl.value)) should be {
            (testConfig1, 1 minute).some
          }
        }
        "there is previous engine" when {
          "same config and ttl" in withReadonlyRestExt({
            val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
            val indexConfigFile = "readonlyrest_index.yml"

            val mockedIndexJsonContentManager = mock[IndexJsonContentService]
            mockIndexJsonContentManagerSourceOfCall(mockedIndexJsonContentManager, resourcesPath + indexConfigFile)

            (mockedIndexJsonContentManager.sourceOf _)
              .expects(fullIndexName(".readonlyrest"), "2")
              .repeated(1)
              .returns(Task.now(Left(ContentNotFound)))

            val coreFactory = mock[CoreFactory]
            mockCoreFactory(coreFactory, resourcesPath + indexConfigFile)
            mockCoreFactory(coreFactory, testConfig1, mockEnabledAccessControl, disabledRorConfig)

            val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(10 seconds))
            (readonlyRest, mockedIndexJsonContentManager)
          }) { case (rorInstance, mockedIndexJsonContentManager) =>
            rorInstance.engines.value.impersonatorsEngine should be(Option.empty)
            rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() should be(TestConfig.NotSet)

            (mockedIndexJsonContentManager.saveContent _)
              .expects(
                where {
                  (config: IndexName.Full, id: String, content: Map[String, String]) =>
                    config == fullIndexName(".readonlyrest") &&
                      id == "2" &&
                      content.get("settings").contains(testConfig1.raw) &&
                      content.get("expiration_ttl_millis").contains("60000") &&
                      content.contains("expiration_timestamp") &&
                      content.contains("auth_services_mocks")
                }
              )
              .repeated(2)
              .returns(Task.now(Right(())))

            val testEngineReloadResult1stAttempt = rorInstance
              .forceReloadTestConfigEngine(testConfig1, (1 minute).toRefinedPositiveUnsafe)(newRequestId())
              .runSyncUnsafe()

            testEngineReloadResult1stAttempt.value shouldBe a[TestConfig.Present]
            rorInstance.engines.value.impersonatorsEngine.value.core.accessControl shouldBe a[AccessControlListLoggingDecorator]

            val testEngineConfig = rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe()
            testEngineConfig shouldBe a[TestConfig.Present]
            Option(testEngineConfig.asInstanceOf[TestConfig.Present]).map(i => (i.rawConfig, i.configuredTtl.value)) should be {
              (testConfig1, 1 minute).some
            }

            val testEngine1Expiration = testEngineConfig.asInstanceOf[TestConfig.Present].validTo

            val testEngineReloadResult2ndAttempt = rorInstance
              .forceReloadTestConfigEngine(testConfig1, (1 minute).toRefinedPositiveUnsafe)(newRequestId())
              .runSyncUnsafe()

            testEngineReloadResult2ndAttempt.value shouldBe a[TestConfig.Present]
            rorInstance.engines.value.impersonatorsEngine.value.core.accessControl shouldBe a[AccessControlListLoggingDecorator]

            val testEngineConfigAfterReload = rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe()
            testEngineConfigAfterReload shouldBe a[TestConfig.Present]
            Option(testEngineConfigAfterReload.asInstanceOf[TestConfig.Present]).map(i => (i.rawConfig, i.configuredTtl.value)) should be {
              (testConfig1, 1 minute).some
            }

            val testEngine2Expiration = testEngineConfigAfterReload.asInstanceOf[TestConfig.Present].validTo

            testEngine2Expiration.isAfter(testEngine1Expiration) should be(true)
          }
          "different ttl" in withReadonlyRestExt({
            val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
            val indexConfigFile = "readonlyrest_index.yml"

            val mockedIndexJsonContentManager = mock[IndexJsonContentService]
            mockIndexJsonContentManagerSourceOfCall(mockedIndexJsonContentManager, resourcesPath + indexConfigFile)

            (mockedIndexJsonContentManager.sourceOf _)
              .expects(fullIndexName(".readonlyrest"), "2")
              .repeated(1)
              .returns(Task.now(Left(ContentNotFound)))

            val coreFactory = mock[CoreFactory]
            mockCoreFactory(coreFactory, resourcesPath + indexConfigFile)
            mockCoreFactory(coreFactory, testConfig1, mockEnabledAccessControl, disabledRorConfig)

            val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(10 seconds))
            (readonlyRest, mockedIndexJsonContentManager)
          }) { case (rorInstance, mockedIndexJsonContentManager) =>
            rorInstance.engines.value.impersonatorsEngine should be(Option.empty)
            rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() should be(TestConfig.NotSet)

            (mockedIndexJsonContentManager.saveContent _)
              .expects(
                where {
                  (config: IndexName.Full, id: String, content: Map[String, String]) =>
                    config == fullIndexName(".readonlyrest") &&
                      id == "2" &&
                      content.get("settings").contains(testConfig1.raw) &&
                      content.get("expiration_ttl_millis").contains("600000") &&
                      content.contains("expiration_timestamp") &&
                      content.contains("auth_services_mocks")
                }
              )
              .repeated(1)
              .returns(Task.now(Right(())))

            val testEngineReloadResult1stAttempt = rorInstance
              .forceReloadTestConfigEngine(testConfig1, (10 minute).toRefinedPositiveUnsafe)(newRequestId())
              .runSyncUnsafe()

            testEngineReloadResult1stAttempt.value shouldBe a[TestConfig.Present]
            rorInstance.engines.value.impersonatorsEngine.value.core.accessControl shouldBe a[AccessControlListLoggingDecorator]

            val testEngineConfig = rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe()
            testEngineConfig shouldBe a[TestConfig.Present]
            Option(testEngineConfig.asInstanceOf[TestConfig.Present])
              .map(i => (i.rawConfig, i.configuredTtl.value)) should be((testConfig1, 10 minute).some)

            val testEngine1Expiration = testEngineConfig.asInstanceOf[TestConfig.Present].validTo

            (mockedIndexJsonContentManager.saveContent _)
              .expects(
                where {
                  (config: IndexName.Full, id: String, content: Map[String, String]) =>
                    config == fullIndexName(".readonlyrest") &&
                      id == "2" &&
                      content.get("settings").contains(testConfig1.raw) &&
                      content.get("expiration_ttl_millis").contains("300000") &&
                      content.contains("expiration_timestamp") &&
                      content.contains("auth_services_mocks")
                }
              )
              .repeated(1)
              .returns(Task.now(Right(())))

            val testEngineReloadResult2ndAttempt = rorInstance
              .forceReloadTestConfigEngine(testConfig1, (5 minute).toRefinedPositiveUnsafe)(newRequestId())
              .runSyncUnsafe()

            testEngineReloadResult2ndAttempt.value shouldBe a[TestConfig.Present]
            rorInstance.engines.value.impersonatorsEngine.value.core.accessControl shouldBe a[AccessControlListLoggingDecorator]

            val testEngineConfigAfterReload = rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe()
            testEngineConfigAfterReload shouldBe a[TestConfig.Present]
            Option(testEngineConfigAfterReload.asInstanceOf[TestConfig.Present])
              .map(i => (i.rawConfig, i.configuredTtl.value)) should be((testConfig1, 5 minute).some)

            val testEngine2Expiration = testEngineConfigAfterReload.asInstanceOf[TestConfig.Present].validTo

            testEngine2Expiration.isAfter(testEngine1Expiration) should be(false)
          }
        }
        "different config is being loaded" in withReadonlyRestExt({
          val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
          val indexConfigFile = "readonlyrest_index.yml"

          val mockedIndexJsonContentManager = mock[IndexJsonContentService]
          mockIndexJsonContentManagerSourceOfCall(mockedIndexJsonContentManager, resourcesPath + indexConfigFile)

          (mockedIndexJsonContentManager.sourceOf _)
            .expects(fullIndexName(".readonlyrest"), "2")
            .repeated(1)
            .returns(Task.now(Left(ContentNotFound)))

          val coreFactory = mock[CoreFactory]
          mockCoreFactory(coreFactory, resourcesPath + indexConfigFile)
          mockCoreFactory(coreFactory, testConfig1, mockEnabledAccessControl, disabledRorConfig)
          mockCoreFactory(coreFactory, testConfig2, mockEnabledAccessControl, disabledRorConfig)

          val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(10 seconds))
          (readonlyRest, mockedIndexJsonContentManager)
        }) { case (rorInstance, mockedIndexJsonContentManager) =>
          rorInstance.engines.value.impersonatorsEngine should be(Option.empty)
          rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() should be(TestConfig.NotSet)

          (mockedIndexJsonContentManager.saveContent _)
            .expects(
              where {
                (config: IndexName.Full, id: String, content: Map[String, String]) =>
                  config == fullIndexName(".readonlyrest") &&
                    id == "2" &&
                    content.get("settings").contains(testConfig1.raw) &&
                    content.get("expiration_ttl_millis").contains("60000") &&
                    content.contains("expiration_timestamp") &&
                    content.contains("auth_services_mocks")
              }
            )
            .repeated(1)
            .returns(Task.now(Right(())))

          val testEngineReloadResult1stAttempt = rorInstance
            .forceReloadTestConfigEngine(testConfig1, (1 minute).toRefinedPositiveUnsafe)(newRequestId())
            .runSyncUnsafe()

          testEngineReloadResult1stAttempt.value shouldBe a[TestConfig.Present]
          rorInstance.engines.value.impersonatorsEngine.value.core.accessControl shouldBe a[AccessControlListLoggingDecorator]

          val testEngineConfig = rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe()
          testEngineConfig shouldBe a[TestConfig.Present]
          Option(testEngineConfig.asInstanceOf[TestConfig.Present]).map(i => (i.rawConfig, i.configuredTtl.value)) should be {
            (testConfig1, 1 minute).some
          }

          val testEngine1Expiration = testEngineConfig.asInstanceOf[TestConfig.Present].validTo

          (mockedIndexJsonContentManager.saveContent _)
            .expects(
              where {
                (config: IndexName.Full, id: String, content: Map[String, String]) =>
                  config == fullIndexName(".readonlyrest") &&
                    id == "2" &&
                    content.get("settings").contains(testConfig2.raw) &&
                    content.get("expiration_ttl_millis").contains("120000") &&
                    content.contains("expiration_timestamp") &&
                    content.contains("auth_services_mocks")
              }
            )
            .repeated(1)
            .returns(Task.now(Right(())))

          val testEngineReloadResult2ndAttempt = rorInstance
            .forceReloadTestConfigEngine(testConfig2, (2 minutes).toRefinedPositiveUnsafe)(newRequestId())
            .runSyncUnsafe()

          testEngineReloadResult2ndAttempt.value shouldBe a[TestConfig.Present]
          rorInstance.engines.value.impersonatorsEngine.value.core.accessControl shouldBe a[AccessControlListLoggingDecorator]

          val testEngineConfigAfterReload = rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe()
          testEngineConfigAfterReload shouldBe a[TestConfig.Present]
          Option(testEngineConfigAfterReload.asInstanceOf[TestConfig.Present])
            .map(i => (i.rawConfig, i.configuredTtl.value)) should be((testConfig2, 2 minutes).some)

          val testEngine2Expiration = testEngineConfigAfterReload.asInstanceOf[TestConfig.Present].validTo

          testEngine2Expiration.isAfter(testEngine1Expiration) should be(true)
        }
      }
      "can be reloaded if index config changes" when {
        "new config and expiration time has not exceeded" in withReadonlyRestExt({
          val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
          val indexConfigFile = "readonlyrest_index.yml"

          val mockedIndexJsonContentManager = mock[IndexJsonContentService]
          mockIndexJsonContentManagerSourceOfCall(mockedIndexJsonContentManager, resourcesPath + indexConfigFile, 2)

          (mockedIndexJsonContentManager.sourceOf _)
            .expects(fullIndexName(".readonlyrest"), "2")
            .repeated(1)
            .returns(Task.now(Left(ContentNotFound)))

          val coreFactory = mock[CoreFactory]
          mockCoreFactory(coreFactory, resourcesPath + indexConfigFile)
          mockCoreFactory(coreFactory, testConfig1, mockEnabledAccessControl, disabledRorConfig)

          val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(2 seconds))
          (readonlyRest, mockedIndexJsonContentManager)
        }) { case (rorInstance, mockedIndexJsonContentManager) =>

          lazy val expirationTimestamp = testClock.instant().plusSeconds(100)
          (mockedIndexJsonContentManager.sourceOf _)
            .expects(fullIndexName(".readonlyrest"), "2")
            .repeated(1)
            .returns(Task.now(Right(
              Map(
                "settings" -> testConfig1.raw,
                "expiration_ttl_millis" -> "100000",
                "expiration_timestamp" -> expirationTimestamp.toString,
                "auth_services_mocks" -> notConfiguredAuthServicesMocksJson,
              )
            )))

          rorInstance.engines.value.impersonatorsEngine should be(Option.empty)
          rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() should be(TestConfig.NotSet)

          Task.sleep(5 seconds).runSyncUnsafe()

          rorInstance.engines.value.impersonatorsEngine.value.core.accessControl shouldBe a[AccessControlListLoggingDecorator]

          rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() should be(
            TestConfig.Present(
              config = RorConfig.disabled,
              rawConfig = testConfig1,
              configuredTtl = (100 seconds).toRefinedPositiveUnsafe,
              validTo = expirationTimestamp
            )
          )
        }
        "same config and the ttl has changed" in withReadonlyRestExt({
          val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
          val indexConfigFile = "readonlyrest_index.yml"

          val mockedIndexJsonContentManager = mock[IndexJsonContentService]
          mockIndexJsonContentManagerSourceOfCall(mockedIndexJsonContentManager, resourcesPath + indexConfigFile, 2)

          lazy val expirationTimestamp = testClock.instant().plusSeconds(100)

          (mockedIndexJsonContentManager.sourceOf _)
            .expects(fullIndexName(".readonlyrest"), "2")
            .repeated(1)
            .returns(Task.now(Right(
              Map(
                "settings" -> testConfig1.raw,
                "expiration_ttl_millis" -> "100000",
                "expiration_timestamp" -> expirationTimestamp.toString,
                "auth_services_mocks" -> notConfiguredAuthServicesMocksJson,
              )
            )))

          val coreFactory = mock[CoreFactory]
          mockCoreFactory(coreFactory, resourcesPath + indexConfigFile)
          mockCoreFactory(coreFactory, testConfig1, mockEnabledAccessControl, disabledRorConfig)

          val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(2 seconds))
          (readonlyRest, (mockedIndexJsonContentManager, expirationTimestamp))
        }) { case (rorInstance, (mockedIndexJsonContentManager, expirationTimestamp)) =>
          rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() should be(
            TestConfig.Present(
              config = RorConfig.disabled,
              rawConfig = testConfig1,
              configuredTtl = (100 seconds).toRefinedPositiveUnsafe,
              validTo = expirationTimestamp
            )
          )

          val expirationTimestamp2 = testClock.instant().plusSeconds(200)
          (mockedIndexJsonContentManager.sourceOf _)
            .expects(fullIndexName(".readonlyrest"), "2")
            .repeated(1)
            .returns(Task.now(Right(
              Map(
                "settings" -> testConfig1.raw,
                "expiration_ttl_millis" -> "200000",
                "expiration_timestamp" -> expirationTimestamp2.toString,
                "auth_services_mocks" -> notConfiguredAuthServicesMocksJson,
              )
            )))

          Task.sleep(5 seconds).runSyncUnsafe()

          rorInstance.engines.value.impersonatorsEngine.value.core.accessControl shouldBe a[AccessControlListLoggingDecorator]

          rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() should be(
            TestConfig.Present(
              config = RorConfig.disabled,
              rawConfig = testConfig1,
              configuredTtl = (200 seconds).toRefinedPositiveUnsafe,
              validTo = expirationTimestamp2
            )
          )
        }
        "same config and the expiration time has changed" in withReadonlyRestExt({
          val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
          val indexConfigFile = "readonlyrest_index.yml"

          val mockedIndexJsonContentManager = mock[IndexJsonContentService]
          mockIndexJsonContentManagerSourceOfCall(mockedIndexJsonContentManager, resourcesPath + indexConfigFile, 2)

          lazy val expirationTimestamp = testClock.instant().plusSeconds(100)

          (mockedIndexJsonContentManager.sourceOf _)
            .expects(fullIndexName(".readonlyrest"), "2")
            .repeated(1)
            .returns(Task.now(Right(
              Map(
                "settings" -> testConfig1.raw,
                "expiration_ttl_millis" -> "100000",
                "expiration_timestamp" -> expirationTimestamp.toString,
                "auth_services_mocks" -> notConfiguredAuthServicesMocksJson,
              )
            )))

          val coreFactory = mock[CoreFactory]
          mockCoreFactory(coreFactory, resourcesPath + indexConfigFile)
          mockCoreFactory(coreFactory, testConfig1, mockEnabledAccessControl, disabledRorConfig)

          val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(2 seconds))
          (readonlyRest, (mockedIndexJsonContentManager, expirationTimestamp))
        }) { case (rorInstance, (mockedIndexJsonContentManager, expirationTimestamp)) =>

          rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() should be(
            TestConfig.Present(
              config = RorConfig.disabled,
              rawConfig = testConfig1,
              configuredTtl = (100 seconds).toRefinedPositiveUnsafe,
              validTo = expirationTimestamp
            )
          )

          val expirationTimestamp2 = testClock.instant().plusSeconds(100)
          (mockedIndexJsonContentManager.sourceOf _)
            .expects(fullIndexName(".readonlyrest"), "2")
            .repeated(1)
            .returns(Task.now(Right(
              Map(
                "settings" -> testConfig1.raw,
                "expiration_ttl_millis" -> "100000",
                "expiration_timestamp" -> expirationTimestamp2.toString,
                "auth_services_mocks" -> notConfiguredAuthServicesMocksJson,
              )
            )))

          Task.sleep(5 seconds).runSyncUnsafe()

          rorInstance.engines.value.impersonatorsEngine.value.core.accessControl shouldBe a[AccessControlListLoggingDecorator]

          rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() should be(
            TestConfig.Present(
              config = RorConfig.disabled,
              rawConfig = testConfig1,
              configuredTtl = (100 seconds).toRefinedPositiveUnsafe,
              validTo = expirationTimestamp2
            )
          )
        }
        "new config and has already expired" in withReadonlyRestExt({
          val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
          val indexConfigFile = "readonlyrest_index.yml"

          val mockedIndexJsonContentManager = mock[IndexJsonContentService]
          mockIndexJsonContentManagerSourceOfCall(mockedIndexJsonContentManager, resourcesPath + indexConfigFile, 2)

          lazy val expirationTimestamp = testClock.instant().plusSeconds(100)

          (mockedIndexJsonContentManager.sourceOf _)
            .expects(fullIndexName(".readonlyrest"), "2")
            .repeated(1)
            .returns(Task.now(Right(
              Map(
                "settings" -> testConfig1.raw,
                "expiration_ttl_millis" -> "100000",
                "expiration_timestamp" -> expirationTimestamp.toString,
                "auth_services_mocks" -> notConfiguredAuthServicesMocksJson,
              )
            )))

          val coreFactory = mock[CoreFactory]
          mockCoreFactory(coreFactory, resourcesPath + indexConfigFile)
          mockCoreFactory(coreFactory, testConfig1, mockEnabledAccessControl, disabledRorConfig)

          val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(2 seconds))
          (readonlyRest, (mockedIndexJsonContentManager, expirationTimestamp))
        }) { case (rorInstance, (mockedIndexJsonContentManager, expirationTimestamp)) =>

          rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() should be(
            TestConfig.Present(
              config = RorConfig.disabled,
              rawConfig = testConfig1,
              configuredTtl = (100 seconds).toRefinedPositiveUnsafe,
              validTo = expirationTimestamp
            )
          )

          val expirationTimestamp2 = testClock.instant().minusSeconds(1)
          (mockedIndexJsonContentManager.sourceOf _)
            .expects(fullIndexName(".readonlyrest"), "2")
            .repeated(1)
            .returns(Task.now(Right(
              Map(
                "settings" -> testConfig2.raw,
                "expiration_ttl_millis" -> "200000",
                "expiration_timestamp" -> expirationTimestamp2.toString,
                "auth_services_mocks" -> notConfiguredAuthServicesMocksJson,
              )
            )))

          Task.sleep(5 seconds).runSyncUnsafe()

          rorInstance.engines.value.impersonatorsEngine should be(Option.empty)

          rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() should be(
            TestConfig.Invalidated(
              recent = testConfig2,
              configuredTtl = (200 seconds).toRefinedPositiveUnsafe
            )
          )
        }
      }
      "should be automatically unloaded" when {
        "engine ttl has reached" in withReadonlyRestExt({
          val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
          val indexConfigFile = "readonlyrest_index.yml"

          val mockedIndexJsonContentManager = mock[IndexJsonContentService]
          mockIndexJsonContentManagerSourceOfCall(mockedIndexJsonContentManager, resourcesPath + indexConfigFile)
          (mockedIndexJsonContentManager.sourceOf _)
            .expects(fullIndexName(".readonlyrest"), "2")
            .repeated(1)
            .returns(Task.now(Left(ContentNotFound)))

          val coreFactory = mock[CoreFactory]
          mockCoreFactory(coreFactory, resourcesPath + indexConfigFile)
          mockCoreFactory(coreFactory, testConfig1, mockEnabledAccessControl, disabledRorConfig)

          val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(0 seconds))
          (readonlyRest, mockedIndexJsonContentManager)
        }) { case (rorInstance, mockedIndexJsonContentManager) =>

          rorInstance.engines.value.impersonatorsEngine should be(Option.empty)
          rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() should be(TestConfig.NotSet)

          (mockedIndexJsonContentManager.saveContent _)
            .expects(
              where {
                (config: IndexName.Full, id: String, content: Map[String, String]) =>
                  config == fullIndexName(".readonlyrest") &&
                    id == "2" &&
                    content.get("settings").contains(testConfig1.raw) &&
                    content.get("expiration_ttl_millis").contains("3000") &&
                    content.contains("expiration_timestamp") &&
                    content.contains("auth_services_mocks")
              }
            )
            .repeated(1)
            .returns(Task.now(Right(())))

          val testEngineReloadResult = rorInstance
            .forceReloadTestConfigEngine(testConfig1, (3 seconds).toRefinedPositiveUnsafe)(newRequestId())
            .runSyncUnsafe()

          testEngineReloadResult.value shouldBe a[TestConfig.Present]
          rorInstance.engines.value.impersonatorsEngine.value.core.accessControl shouldBe a[AccessControlListLoggingDecorator]

          Task.sleep(5 seconds).runSyncUnsafe()

          rorInstance.engines.value.impersonatorsEngine should be(Option.empty)
          rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() should be(
            TestConfig.Invalidated(testConfig1, (3 seconds).toRefinedPositiveUnsafe)
          )
        }
      }
      "can be invalidated by user" in withReadonlyRestExt({
        val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
        val indexConfigFile = "readonlyrest_index.yml"

        val mockedIndexJsonContentManager = mock[IndexJsonContentService]
        mockIndexJsonContentManagerSourceOfCall(mockedIndexJsonContentManager, resourcesPath + indexConfigFile)

        (mockedIndexJsonContentManager.sourceOf _)
          .expects(fullIndexName(".readonlyrest"), "2")
          .repeated(1)
          .returns(Task.now(Left(ContentNotFound)))

        val coreFactory = mock[CoreFactory]
        mockCoreFactory(coreFactory, resourcesPath + indexConfigFile)
        mockCoreFactory(coreFactory, testConfig1, mockEnabledAccessControl, disabledRorConfig)

        val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(10 seconds))
        (readonlyRest, mockedIndexJsonContentManager)
      }) { case (rorInstance, mockedIndexJsonContentManager) =>
        rorInstance.engines.value.impersonatorsEngine should be(Option.empty)
        rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() should be(TestConfig.NotSet)

        (mockedIndexJsonContentManager.saveContent _)
          .expects(
            where {
              (config: IndexName.Full, id: String, content: Map[String, String]) =>
                config == fullIndexName(".readonlyrest") &&
                  id == "2" &&
                  content.get("settings").contains(testConfig1.raw) &&
                  content.get("expiration_ttl_millis").contains("60000") &&
                  content.contains("expiration_timestamp") &&
                  content.contains("auth_services_mocks")
            }
          )
          .repeated(1)
          .returns(Task.now(Right(())))

        val testEngineReloadResult = rorInstance
          .forceReloadTestConfigEngine(testConfig1, (1 minute).toRefinedPositiveUnsafe)(newRequestId())
          .runSyncUnsafe()

        testEngineReloadResult.value shouldBe a[TestConfig.Present]
        rorInstance.engines.value.impersonatorsEngine.value.core.accessControl shouldBe a[AccessControlListLoggingDecorator]
        rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() shouldBe a[TestConfig.Present]

        (mockedIndexJsonContentManager.saveContent _)
          .expects(
            where {
              (config: IndexName.Full, id: String, content: Map[String, String]) =>
                config == fullIndexName(".readonlyrest") &&
                  id == "2" &&
                  content.get("settings").contains(testConfig1.raw) &&
                  content.get("expiration_ttl_millis").contains("60000") &&
                  content.contains("expiration_timestamp") &&
                  content.contains("auth_services_mocks")
            }
          )
          .repeated(1)
          .returns(Task.now(Right(())))

        rorInstance.invalidateTestConfigEngine()(newRequestId()).runSyncUnsafe() should be(Right(()))

        rorInstance.engines.value.impersonatorsEngine should be(Option.empty)
        rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() should be(
          TestConfig.Invalidated(recent = testConfig1, configuredTtl = (1 minute).toRefinedPositiveUnsafe)
        )
      }
      "should return error for invalidation" when {
        "cannot save invalidation timestamp in index" in withReadonlyRestExt({
          val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
          val indexConfigFile = "readonlyrest_index.yml"

          val mockedIndexJsonContentManager = mock[IndexJsonContentService]
          mockIndexJsonContentManagerSourceOfCall(mockedIndexJsonContentManager, resourcesPath + indexConfigFile)

          lazy val expirationTimestamp = testClock.instant().plusSeconds(100)
          (mockedIndexJsonContentManager.sourceOf _)
            .expects(fullIndexName(".readonlyrest"), "2")
            .repeated(1)
            .returns(Task.now(Right(
              Map(
                "settings" -> testConfig1.raw,
                "expiration_ttl_millis" -> "100000",
                "expiration_timestamp" -> expirationTimestamp.toString,
                "auth_services_mocks" -> notConfiguredAuthServicesMocksJson,
              )
            )))

          val coreFactory = mock[CoreFactory]
          mockCoreFactory(coreFactory, resourcesPath + indexConfigFile)
          mockCoreFactory(coreFactory, testConfig1, mockEnabledAccessControl, disabledRorConfig)

          val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(10 seconds))
          (readonlyRest, (mockedIndexJsonContentManager, expirationTimestamp))
        }) { case (rorInstance, (mockedIndexJsonContentManager, expirationTimestamp)) =>
          rorInstance.engines.value.impersonatorsEngine.value.core.accessControl shouldBe a[AccessControlListLoggingDecorator]
          rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() should be(
            TestConfig.Present(
              config = RorConfig.disabled,
              rawConfig = testConfig1,
              configuredTtl = (100 seconds).toRefinedPositiveUnsafe,
              validTo = expirationTimestamp
            )
          )

          (mockedIndexJsonContentManager.saveContent _)
            .expects(
              where {
                (config: IndexName.Full, id: String, content: Map[String, String]) =>
                  config == fullIndexName(".readonlyrest") &&
                    id == "2" &&
                    content.get("settings").contains(testConfig1.raw) &&
                    content.get("expiration_ttl_millis").contains("100000") &&
                    content.get("expiration_timestamp").exists(_ != expirationTimestamp.toString)
              }
            )
            .repeated(1)
            .returns(Task.now(Left(CannotWriteToIndex)))

          rorInstance.invalidateTestConfigEngine()(newRequestId()).runSyncUnsafe() should be(
            Left(IndexConfigInvalidationError.IndexConfigSavingError(SavingIndexConfigError.CannotSaveConfig))
          )

          rorInstance.engines.value.impersonatorsEngine should be(Option.empty)
          rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() should be(
            TestConfig.Invalidated(testConfig1, (100 seconds).toRefinedPositiveUnsafe)
          )
        }
      }
    }
    "not be able to be loaded" when {
      "max size of ROR settings is exceeded" in {
        val readonlyRest = readonlyRestBoot(
          mock[CoreFactory],
          mock[IndexJsonContentService],
          "/boot_tests/forced_file_loading/",
          maxYamlSize = Some("1 B")
        )

        val result = readonlyRest.start().runSyncUnsafe()
        inside(result) {
          case Left(StartingFailure(message, _)) =>
            message should include("Settings file is malformed")
            message should include("The incoming YAML document exceeds the limit: 1 code points")
        }
      }
      "unable to setup data stream audit output" in {
        val mockedIndexJsonContentManager = mock[IndexJsonContentService]
        mockIndexJsonContentManagerSourceOfCallTestConfig(mockedIndexJsonContentManager)

        val dataStreamSinkConfig1 = AuditSink.Config.EsDataStreamBasedSink.default(testAuditEnvironmentContext)
        val dataStreamSinkConfig2 = dataStreamSinkConfig1.copy(
          auditCluster = AuditCluster.RemoteAuditCluster(NonEmptyList.one(Uri.parse("0.0.0.0")))
        )

        val coreFactory = mockCoreFactory(
          mock[CoreFactory],
          "/boot_tests/forced_file_loading_with_audit/readonlyrest.yml",
          mockEnabledAccessControl,
          RorConfig(RorConfig.Services.empty, LocalUsers.empty, NoOpImpersonationWarningsReader, Some(AuditingTool.AuditSettings(
            NonEmptyList.of(
              AuditSink.Enabled(dataStreamSinkConfig1),
              AuditSink.Enabled(dataStreamSinkConfig2))
          )
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

        val readonlyRest = readonlyRestBoot(
          coreFactory,
          mockedIndexJsonContentManager,
          "/boot_tests/forced_file_loading_with_audit/",
          auditSinkServiceCreator
        )

        val result = readonlyRest.start().runSyncUnsafe()
        inside(result) {
          case Left(StartingFailure(message, _)) =>
            val expectedMessage =
              s"""Errors:
                 |Unable to configure audit output using a data stream in local cluster. Details: [Failed to setup ROR audit data stream readonlyrest_audit. Reason: Unable to determine if the index lifecycle policy with ID 'readonlyrest_audit-lifecycle-policy' has been created]
                 |Unable to configure audit output using a data stream in remote cluster 0.0.0.0. Details: [Failed to setup ROR audit data stream readonlyrest_audit. Reason: Unable to determine if component template with ID 'readonlyrest_audit-mappings' has been created]""".stripMargin
            message should be(expectedMessage)
        }
      }
    }
  }

  private def withReadonlyRest(readonlyRest: ReadonlyRest)(testCode: RorInstance => Any): Unit = {
    withReadonlyRestExt((readonlyRest, ())) { case (rorInstance, ()) => testCode(rorInstance) }
  }

  private def withReadonlyRestExt[EXT](readonlyRestAndExt: (ReadonlyRest, EXT))
                                      (testCode: (RorInstance, EXT) => Any): Unit = {
    val (readonlyRest, ext) = readonlyRestAndExt
    Resource
      .make(
        acquire = readonlyRest
          .start()
          .flatMap {
            case Right(startedInstance) => Task.now(startedInstance)
            case Left(startingFailure) => Task.raiseError(new Exception(s"$startingFailure"))
          }
      )(
        release = _.stop()
      )
      .use { startedInstance =>
        Task.delay {
          testCode(startedInstance, ext)
        }
      }
      .runSyncUnsafe()
  }

  private def readonlyRestBoot(factory: CoreFactory,
                               indexJsonContentService: IndexJsonContentService,
                               configPath: String,
                               auditSinkServiceCreator: AuditSinkServiceCreator = mock[AuditSinkServiceCreator],
                               refreshInterval: Option[FiniteDuration] = None,
                               maxYamlSize: Option[String] = None): ReadonlyRest = {
    def mapWithIntervalFrom(refreshInterval: Option[FiniteDuration]) =
      refreshInterval
        .map(i => "com.readonlyrest.settings.refresh.interval" -> i.toSeconds.toString)
        .toMap

    def mapWithMaxYamlSize(maxYamlSize: Option[String]) =
      maxYamlSize
        .map(size => "com.readonlyrest.settings.maxSize" -> size)
        .toMap

    implicit val environmentConfig: EnvironmentConfig = new EnvironmentConfig(
      propertiesProvider = TestsPropertiesProvider.usingMap(
        mapWithIntervalFrom(refreshInterval) ++
          mapWithMaxYamlSize(maxYamlSize) ++
          Map(
            "com.readonlyrest.settings.loading.delay" -> "1",
            "com.readonlyrest.settings.loading.attempts.count" -> "1"
          )
      )
    )

    ReadonlyRest.create(
      factory,
      indexJsonContentService,
      auditSinkServiceCreator,
      EsEnv(getResourcePath(configPath), getResourcePath(configPath), defaultEsVersionForTests, testEsNodeSettings),
    )
  }

  private def mockIndexJsonContentManagerSourceOfCall(mockedManager: IndexJsonContentService,
                                                      resourceFileName: String,
                                                      repeatedCount: Int = 1) = {
    (mockedManager.sourceOf _)
      .expects(fullIndexName(".readonlyrest"), "1")
      .repeated(repeatedCount)
      .returns(Task.now(Right(
        Map("settings" -> getResourceContent(resourceFileName))
      )))
    mockedManager
  }

  private def mockIndexJsonContentManagerSourceOfCallTestConfig(mockedManager: IndexJsonContentService) = {
    (mockedManager.sourceOf _)
      .expects(fullIndexName(".readonlyrest"), "2")
      .anyNumberOfTimes()
      .returns(Task.now(Left(ContentNotFound)))
    mockedManager
  }

  private def mockIndexJsonContentManagerSaveCall(mockedManager: IndexJsonContentService,
                                                  resourceFileName: String,
                                                  saveResult: Task[Either[WriteError, Unit]] = Task.now(Right(()))) = {
    (mockedManager.saveContent _)
      .expects(fullIndexName(".readonlyrest"), "1", Map("settings" -> getResourceContent(resourceFileName)))
      .once()
      .returns(saveResult)
    mockedManager
  }

  private def mockCoreFactory(mockedCoreFactory: CoreFactory,
                              resourceFileName: String,
                              accessControlMock: AccessControlList = mockEnabledAccessControl,
                              rorConfig: RorConfig = RorConfig.disabled): CoreFactory = {
    mockCoreFactory(mockedCoreFactory, rorConfigFromResource(resourceFileName), accessControlMock, rorConfig)
  }

  private def mockCoreFactory(mockedCoreFactory: CoreFactory,
                              rawRorConfig: RawRorConfig,
                              accessControlMock: AccessControlList,
                              rorConfig: RorConfig): CoreFactory = {
    (mockedCoreFactory.createCoreFrom _)
      .expects(where {
        (config: RawRorConfig, _, _, _, _) => config == rawRorConfig
      })
      .once()
      .returns(Task.now(Right(Core(accessControlMock, rorConfig))))
    mockedCoreFactory
  }

  private def disabledRorConfig = RorConfig.disabled

  private def mockCoreFactory(mockedCoreFactory: CoreFactory,
                              resourceFileName: String,
                              createCoreResult: Task[Either[NonEmptyList[CoreCreationError], Core]]) = {
    (mockedCoreFactory.createCoreFrom _)
      .expects(where {
        (config: RawRorConfig, _, _, _, _) => config == rorConfigFromResource(resourceFileName)
      })
      .once()
      .returns(createCoreResult)
    mockedCoreFactory
  }

  private def mockFailedCoreFactory(mockedCoreFactory: CoreFactory,
                                    resourceFileName: String): CoreFactory = {
    mockFailedCoreFactory(mockedCoreFactory, rorConfigFromResource(resourceFileName))
  }

  private def mockFailedCoreFactory(mockedCoreFactory: CoreFactory,
                                    rawRorConfig: RawRorConfig): CoreFactory = {
    (mockedCoreFactory.createCoreFrom _)
      .expects(where {
        (config: RawRorConfig, _, _, _, _) => config == rawRorConfig
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

  private lazy val testConfig1 = rorConfigFromUnsafe(
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

  private lazy val testConfig2 = rorConfigFromUnsafe(
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

  private lazy val testConfigMalformed = rorConfigFromUnsafe(
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
