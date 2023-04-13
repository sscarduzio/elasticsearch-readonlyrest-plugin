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
import cats.implicits._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Positive
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers.{a, _}
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{EitherValues, Inside, OptionValues}
import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.AccessControl
import tech.beshu.ror.accesscontrol.AccessControl.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService
import tech.beshu.ror.accesscontrol.blocks.definitions.{ExternalAuthenticationService, ExternalAuthorizationService}
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider.{ExternalAuthenticationServiceMock, ExternalAuthorizationServiceMock, LdapServiceMock}
import tech.beshu.ror.accesscontrol.domain.GroupLike.GroupName
import tech.beshu.ror.accesscontrol.domain.{IndexName, User}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.{Core, CoreFactory}
import tech.beshu.ror.accesscontrol.logging.AccessControlLoggingDecorator
import tech.beshu.ror.boot.ReadonlyRest
import tech.beshu.ror.boot.RorInstance.{IndexConfigInvalidationError, TestConfig}
import tech.beshu.ror.configuration.index.SavingIndexConfigError
import tech.beshu.ror.configuration.{RawRorConfig, RorConfig}
import tech.beshu.ror.es.IndexJsonContentService.{CannotReachContentSource, CannotWriteToIndex, ContentNotFound, WriteError}
import tech.beshu.ror.es.{AuditSinkService, IndexJsonContentService}
import tech.beshu.ror.providers.{EnvVarsProvider, OsEnvVarsProvider, PropertiesProvider}
import tech.beshu.ror.utils.DurationOps._
import tech.beshu.ror.utils.TestsPropertiesProvider
import tech.beshu.ror.utils.TestsUtils._

import java.time.Clock
import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._
import scala.language.{implicitConversions, postfixOps}

class ReadonlyRestStartingTests
  extends AnyWordSpec
    with Inside with OptionValues with EitherValues
    with MockFactory with Eventually {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(15, Seconds)), interval = scaled(Span(100, Millis)))
  implicit private val envVarsProvider: EnvVarsProvider = OsEnvVarsProvider

  private implicit val testClock: Clock = Clock.systemUTC()

  "A ReadonlyREST core" should {
    "support the main engine" should {
      "be loaded from file" when {
        "index is not available but file config is provided" in {
          val mockedIndexJsonContentManager = mock[IndexJsonContentService]
          (mockedIndexJsonContentManager.sourceOf _)
            .expects(fullIndexName(".readonlyrest"), "1")
            .repeated(5)
            .returns(Task.now(Left(CannotReachContentSource)))

          mockIndexJsonContentManagerSourceOfCallTestConfig(mockedIndexJsonContentManager)

          val coreFactory = mockCoreFactory(mock[CoreFactory], "/boot_tests/no_index_config_file_config_provided/readonlyrest.yml")
          val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, "/boot_tests/no_index_config_file_config_provided/")

          val result = readonlyRest.start().runSyncUnsafe()

          val acl = result.value.engines.value.mainEngine.core.accessControl
          acl shouldBe a[AccessControlLoggingDecorator]
          acl.asInstanceOf[AccessControlLoggingDecorator].underlying shouldBe a[EnabledAcl]
        }
        "file loading is forced in elasticsearch.yml" in {
          val mockedIndexJsonContentManager = mock[IndexJsonContentService]
          mockIndexJsonContentManagerSourceOfCallTestConfig(mockedIndexJsonContentManager)

          val coreFactory = mockCoreFactory(mock[CoreFactory], "/boot_tests/forced_file_loading/readonlyrest.yml")
          val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, "/boot_tests/forced_file_loading/")

          val result = readonlyRest.start().runSyncUnsafe()

          val acl = result.value.engines.value.mainEngine.core.accessControl
          acl shouldBe a[AccessControlLoggingDecorator]
          acl.asInstanceOf[AccessControlLoggingDecorator].underlying shouldBe a[EnabledAcl]
        }
      }
      "be loaded from index" when {
        "index is available and file config is provided" in {
          val resourcesPath = "/boot_tests/index_config_available_file_config_provided/"
          val indexConfigFile = "readonlyrest_index.yml"

          val mockedIndexJsonContentManager = mock[IndexJsonContentService]
          mockIndexJsonContentManagerSourceOfCall(mockedIndexJsonContentManager, resourcesPath + indexConfigFile)
          mockIndexJsonContentManagerSourceOfCallTestConfig(mockedIndexJsonContentManager)

          val coreFactory = mockCoreFactory(mock[CoreFactory], resourcesPath + indexConfigFile)
          val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath)

          val result = readonlyRest.start().runSyncUnsafe()

          val acl = result.value.engines.value.mainEngine.core.accessControl
          acl shouldBe a[AccessControlLoggingDecorator]
          acl.asInstanceOf[AccessControlLoggingDecorator].underlying shouldBe a[EnabledAcl]
        }
        "index is available and file config is not provided" in {
          val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
          val indexConfigFile = "readonlyrest_index.yml"

          val mockedIndexJsonContentManager = mock[IndexJsonContentService]
          mockIndexJsonContentManagerSourceOfCall(mockedIndexJsonContentManager, resourcesPath + indexConfigFile)
          mockIndexJsonContentManagerSourceOfCallTestConfig(mockedIndexJsonContentManager)

          val coreFactory = mockCoreFactory(mock[CoreFactory], resourcesPath + indexConfigFile)

          val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath)

          val result = readonlyRest.start().runSyncUnsafe()

          val acl = result.value.engines.value.mainEngine.core.accessControl
          acl shouldBe a[AccessControlLoggingDecorator]
          acl.asInstanceOf[AccessControlLoggingDecorator].underlying shouldBe a[EnabledAcl]
        }
      }
      "be able to be reloaded" when {
        "new config is different than old one" in {
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

          val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(0 seconds))

          val result = readonlyRest.start().runSyncUnsafe()

          val instance = result.value
          val mainEngine = instance.engines.value.mainEngine
          mainEngine.core.accessControl shouldBe a[AccessControlLoggingDecorator]
          mainEngine.core.accessControl.asInstanceOf[AccessControlLoggingDecorator].underlying shouldBe a[EnabledAcl]

          val reload1Result = instance
            .forceReloadAndSave(rorConfigFromResource(resourcesPath + newIndexConfigFile))(newRequestId())
            .runSyncUnsafe()

          reload1Result should be(Right(()))
          assert(mainEngine != instance.engines.value.mainEngine, "Engine was not reloaded")
        }
        "two parallel force reloads are invoked" in {
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

          val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(0 seconds))

          val result = readonlyRest.start().runSyncUnsafe()

          val instance = result.value
          val acl = eventually {
            instance.engines.value.mainEngine.core.accessControl
          }

          val results = Task
            .parSequence(List(
              instance
                .forceReloadAndSave(rorConfigFromResource(resourcesPath + firstNewIndexConfigFile))(newRequestId())
                .map { result =>
                  // schedule after first finish
                  mockIndexJsonContentManagerSaveCall(mockedIndexJsonContentManager, resourcesPath + secondNewIndexConfigFile)
                  result
                },
              Task
                .sleep(200 millis)
                .flatMap { _ =>
                  instance.forceReloadAndSave(rorConfigFromResource(resourcesPath + secondNewIndexConfigFile))(newRequestId())
                }
            ))
            .runSyncUnsafe()
            .sequence

          results should be(Right(List((), ())))
        }
      }
      "be reloaded if index config changes" in {
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

        val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(2 seconds))

        val result = readonlyRest.start().flatMap { result =>
          val acl = result.value.engines.value.mainEngine.core.accessControl
          acl shouldBe a[AccessControlLoggingDecorator]
          acl.asInstanceOf[AccessControlLoggingDecorator].underlying shouldBe a[DisabledAcl]

          Task
            .sleep(4 seconds)
            .map(_ => result)
        }
          .runSyncUnsafe()

        val acl = result.value.engines.value.mainEngine.core.accessControl
        acl shouldBe a[AccessControlLoggingDecorator]
        acl.asInstanceOf[AccessControlLoggingDecorator].underlying shouldBe a[EnabledAcl]
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
            .repeated(5)
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
            .repeated(5)
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
      }
    }
    "support the test engine" which {
      "can be initialized" when {
        "there is no config in index" in {
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

          val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(5 seconds))

          val result = readonlyRest.start().runSyncUnsafe()

          val rorInstance = result.value
          rorInstance.engines.value.impersonatorsEngine should be(Option.empty)

          rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() should be(TestConfig.NotSet)
        }
        "there is some config stored in index" should {
          "load test engine as active" when {
            "config is still valid" in {
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
              mockCoreFactory(coreFactory, testConfig1, mockEnabledAccessControl)

              val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(10 seconds))

              val result = readonlyRest.start().runSyncUnsafe()

              val rorInstance = result.value
              rorInstance.engines.value.impersonatorsEngine.value.core.accessControl shouldBe a[AccessControlLoggingDecorator]

              rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() should be(
                TestConfig.Present(
                  config = RorConfig.disabled,
                  rawConfig = testConfig1,
                  configuredTtl = FiniteDuration(100, TimeUnit.SECONDS),
                  validTo = expirationTimestamp
                )
              )

              rorInstance.mocksProvider.ldapServiceWith(LdapService.Name("ldap1"))(newRequestId()) should be(Some(
                LdapServiceMock(Set(LdapServiceMock.LdapUserMock(User.Id("Tom"), Set(GroupName("group1"), GroupName("group2")))))
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
                    groups = Set(GroupName("group3"), GroupName("group4"))
                  )
                ))
              ))
              rorInstance.mocksProvider.externalAuthorizationServiceWith(ExternalAuthorizationService.Name("grp2"))(newRequestId()) should be(None)
            }
          }
          "load test engine as invalidated" when {
            "the expiration timestamp exceeded" in {
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

              val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(10 seconds))

              val result = readonlyRest.start().runSyncUnsafe()

              val rorInstance = result.value
              rorInstance.engines.value.impersonatorsEngine should be(Option.empty)

              rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() should be(
                TestConfig.Invalidated(
                  recent = testConfig1,
                  configuredTtl = FiniteDuration(100, TimeUnit.SECONDS)
                )
              )
            }
          }
        }
        "index is not accessible" should {
          "fallbacks to not configured" in {
            val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
            val indexConfigFile = "readonlyrest_index.yml"

            val mockedIndexJsonContentManager = mock[IndexJsonContentService]
            mockIndexJsonContentManagerSourceOfCall(mockedIndexJsonContentManager, resourcesPath + indexConfigFile)

            (mockedIndexJsonContentManager.sourceOf _)
              .expects(fullIndexName(".readonlyrest"), "2")
              .repeated(5)
              .returns(Task.now(Left(CannotReachContentSource)))

            val coreFactory = mock[CoreFactory]
            mockCoreFactory(coreFactory, resourcesPath + indexConfigFile)

            val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(5 seconds))

            val result = readonlyRest.start().runSyncUnsafe()

            val rorInstance = result.value
            rorInstance.engines.value.impersonatorsEngine should be(Option.empty)

            rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() should be(TestConfig.NotSet)
          }
        }
        "settings structure is not valid" should {
          "fallback to not configured" in {
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

            val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(5 seconds))

            val result = readonlyRest.start().runSyncUnsafe()

            val rorInstance = result.value
            rorInstance.engines.value.impersonatorsEngine should be(Option.empty)

            rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() should be(TestConfig.NotSet)
          }
        }
        "settings structure is valid, rule is malformed and cannot start engine" should {
          "fallback to invalidated config" in {
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

            val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(5 seconds))

            val result = readonlyRest.start().runSyncUnsafe()
            val rorInstance = result.value
            rorInstance.engines.value.impersonatorsEngine should be(Option.empty)
            rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() should be(
              TestConfig.Invalidated(
                recent = testConfigMalformed,
                configuredTtl = FiniteDuration(100, TimeUnit.SECONDS)
              )
            )
          }
        }
      }
      "can be loaded on demand" when {
        "there is no previous engine" in {
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
          mockCoreFactory(coreFactory, testConfig1, mockEnabledAccessControl)

          val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(10 seconds))

          val result = readonlyRest.start().runSyncUnsafe()

          val rorInstance = result.value
          rorInstance.engines.value.impersonatorsEngine should be(Option.empty)

          rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() should be(TestConfig.NotSet)

          (mockedIndexJsonContentManager.saveContent _)
            .expects(
              where {
                (config: IndexName.Full, id: String, content: Map[String, String]) =>
                  config == fullIndexName(".readonlyrest") &&
                    id == "2"  &&
                    content.get("settings").contains(testConfig1.raw) &&
                    content.get("expiration_ttl_millis").contains("60000") &&
                    content.contains("expiration_timestamp") &&
                    content.contains("auth_services_mocks")
              }
            )
            .repeated(1)
            .returns(Task.now(Right(())))

          val testEngineReloadResult = rorInstance
            .forceReloadTestConfigEngine(testConfig1, 1 minute)(newRequestId())
            .runSyncUnsafe()

          testEngineReloadResult.value shouldBe a[TestConfig.Present]
          rorInstance.engines.value.impersonatorsEngine.value.core.accessControl shouldBe a[AccessControlLoggingDecorator]

          val testEngineConfig = rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe()
          testEngineConfig shouldBe a[TestConfig.Present]
          Option(testEngineConfig.asInstanceOf[TestConfig.Present])
            .map(i => (i.rawConfig, i.configuredTtl.value)) should be((testConfig1, 1 minute).some)
        }
        "there is previous engine" when {
          "same config and ttl" in {
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
            mockCoreFactory(coreFactory, testConfig1, mockEnabledAccessControl)

            val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(10 seconds))

            val result = readonlyRest.start().runSyncUnsafe()

            val rorInstance = result.value
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
              .forceReloadTestConfigEngine(testConfig1, 1 minute)(newRequestId())
              .runSyncUnsafe()

            testEngineReloadResult1stAttempt.value shouldBe a[TestConfig.Present]
            rorInstance.engines.value.impersonatorsEngine.value.core.accessControl shouldBe a[AccessControlLoggingDecorator]

            val testEngineConfig = rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe()
            testEngineConfig shouldBe a[TestConfig.Present]
            Option(testEngineConfig.asInstanceOf[TestConfig.Present])
              .map(i => (i.rawConfig, i.configuredTtl.value)) should be((testConfig1, 1 minute).some)

            val testEngine1Expiration = testEngineConfig.asInstanceOf[TestConfig.Present].validTo

            val testEngineReloadResult2ndAttempt = rorInstance
              .forceReloadTestConfigEngine(testConfig1, 1 minute)(newRequestId())
              .runSyncUnsafe()

            testEngineReloadResult2ndAttempt.value shouldBe a[TestConfig.Present]
            rorInstance.engines.value.impersonatorsEngine.value.core.accessControl shouldBe a[AccessControlLoggingDecorator]

            val testEngineConfigAfterReload = rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe()
            testEngineConfigAfterReload shouldBe a[TestConfig.Present]
            Option(testEngineConfigAfterReload.asInstanceOf[TestConfig.Present])
              .map(i => (i.rawConfig, i.configuredTtl.value)) should be((testConfig1, 1 minute).some)

            val testEngine2Expiration = testEngineConfigAfterReload.asInstanceOf[TestConfig.Present].validTo

            testEngine2Expiration.isAfter(testEngine1Expiration) should be(true)
          }
          "different ttl" in {
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
            mockCoreFactory(coreFactory, testConfig1, mockEnabledAccessControl)

            val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(10 seconds))

            val result = readonlyRest.start().runSyncUnsafe()

            val rorInstance = result.value
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
              .forceReloadTestConfigEngine(testConfig1, 10 minute)(newRequestId())
              .runSyncUnsafe()

            testEngineReloadResult1stAttempt.value shouldBe a[TestConfig.Present]
            rorInstance.engines.value.impersonatorsEngine.value.core.accessControl shouldBe a[AccessControlLoggingDecorator]

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
              .forceReloadTestConfigEngine(testConfig1, 5 minute)(newRequestId())
              .runSyncUnsafe()

            testEngineReloadResult2ndAttempt.value shouldBe a[TestConfig.Present]
            rorInstance.engines.value.impersonatorsEngine.value.core.accessControl shouldBe a[AccessControlLoggingDecorator]

            val testEngineConfigAfterReload = rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe()
            testEngineConfigAfterReload shouldBe a[TestConfig.Present]
            Option(testEngineConfigAfterReload.asInstanceOf[TestConfig.Present])
              .map(i => (i.rawConfig, i.configuredTtl.value)) should be((testConfig1, 5 minute).some)

            val testEngine2Expiration = testEngineConfigAfterReload.asInstanceOf[TestConfig.Present].validTo

            testEngine2Expiration.isAfter(testEngine1Expiration) should be(false)
          }
        }
        "different config is being loaded" in {
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
          mockCoreFactory(coreFactory, testConfig1, mockEnabledAccessControl)
          mockCoreFactory(coreFactory, testConfig2, mockEnabledAccessControl)

          val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(10 seconds))

          val result = readonlyRest.start().runSyncUnsafe()

          val rorInstance = result.value
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
            .forceReloadTestConfigEngine(testConfig1, 1 minute)(newRequestId())
            .runSyncUnsafe()

          testEngineReloadResult1stAttempt.value shouldBe a[TestConfig.Present]
          rorInstance.engines.value.impersonatorsEngine.value.core.accessControl shouldBe a[AccessControlLoggingDecorator]

          val testEngineConfig = rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe()
          testEngineConfig shouldBe a[TestConfig.Present]
          Option(testEngineConfig.asInstanceOf[TestConfig.Present])
            .map(i => (i.rawConfig, i.configuredTtl.value)) should be((testConfig1, 1 minute).some)

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
            .forceReloadTestConfigEngine(testConfig2, 2 minutes)(newRequestId())
            .runSyncUnsafe()

          testEngineReloadResult2ndAttempt.value shouldBe a[TestConfig.Present]
          rorInstance.engines.value.impersonatorsEngine.value.core.accessControl shouldBe a[AccessControlLoggingDecorator]

          val testEngineConfigAfterReload = rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe()
          testEngineConfigAfterReload shouldBe a[TestConfig.Present]
          Option(testEngineConfigAfterReload.asInstanceOf[TestConfig.Present])
            .map(i => (i.rawConfig, i.configuredTtl.value)) should be((testConfig2, 2 minutes).some)

          val testEngine2Expiration = testEngineConfigAfterReload.asInstanceOf[TestConfig.Present].validTo

          testEngine2Expiration.isAfter(testEngine1Expiration) should be(true)
        }
      }
      "can be reloaded if index config changes" when {
        "new config and expiration time has not exceeded" in {
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
          mockCoreFactory(coreFactory, testConfig1, mockEnabledAccessControl)

          val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(2 seconds))

          val result = readonlyRest.start().runSyncUnsafe()

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

          val rorInstance = result.value
          rorInstance.engines.value.impersonatorsEngine should be(Option.empty)
          rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() should be(TestConfig.NotSet)

          Task.sleep(5 seconds).runSyncUnsafe()

          rorInstance.engines.value.impersonatorsEngine.value.core.accessControl shouldBe a[AccessControlLoggingDecorator]

          rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() should be(
            TestConfig.Present(
              config = RorConfig.disabled,
              rawConfig = testConfig1,
              configuredTtl = FiniteDuration(100, TimeUnit.SECONDS),
              validTo = expirationTimestamp
            )
          )
        }
        "same config and the ttl has changed" in {
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
          mockCoreFactory(coreFactory, testConfig1, mockEnabledAccessControl)

          val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(2 seconds))

          val result = readonlyRest.start().runSyncUnsafe()
          val rorInstance = result.value
          rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() should be(
            TestConfig.Present(
              config = RorConfig.disabled,
              rawConfig = testConfig1,
              configuredTtl = FiniteDuration(100, TimeUnit.SECONDS),
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

          rorInstance.engines.value.impersonatorsEngine.value.core.accessControl shouldBe a[AccessControlLoggingDecorator]

          rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() should be(
            TestConfig.Present(
              config = RorConfig.disabled,
              rawConfig = testConfig1,
              configuredTtl = FiniteDuration(200, TimeUnit.SECONDS),
              validTo = expirationTimestamp2
            )
          )
        }
        "same config and the expiration time has changed" in {
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
          mockCoreFactory(coreFactory, testConfig1, mockEnabledAccessControl)

          val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(2 seconds))

          val result = readonlyRest.start().runSyncUnsafe()
          val rorInstance = result.value
          rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() should be(
            TestConfig.Present(
              config = RorConfig.disabled,
              rawConfig = testConfig1,
              configuredTtl = FiniteDuration(100, TimeUnit.SECONDS),
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

          rorInstance.engines.value.impersonatorsEngine.value.core.accessControl shouldBe a[AccessControlLoggingDecorator]

          rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() should be(
            TestConfig.Present(
              config = RorConfig.disabled,
              rawConfig = testConfig1,
              configuredTtl = FiniteDuration(100, TimeUnit.SECONDS),
              validTo = expirationTimestamp2
            )
          )
        }
        "new config and has already expired" in {
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
          mockCoreFactory(coreFactory, testConfig1, mockEnabledAccessControl)

          val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(2 seconds))

          val result = readonlyRest.start().runSyncUnsafe()
          val rorInstance = result.value
          rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() should be(
            TestConfig.Present(
              config = RorConfig.disabled,
              rawConfig = testConfig1,
              configuredTtl = FiniteDuration(100, TimeUnit.SECONDS),
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
              configuredTtl = FiniteDuration(200, TimeUnit.SECONDS)
            )
          )
        }
      }
      "should be automatically unloaded" when {
        "engine ttl has reached" in {
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
          mockCoreFactory(coreFactory, testConfig1, mockEnabledAccessControl)

          val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(0 seconds))

          val result = readonlyRest.start().runSyncUnsafe()

          val rorInstance = result.value
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
            .forceReloadTestConfigEngine(testConfig1, 3 seconds)(newRequestId())
            .runSyncUnsafe()

          testEngineReloadResult.value shouldBe a[TestConfig.Present]
          rorInstance.engines.value.impersonatorsEngine.value.core.accessControl shouldBe a[AccessControlLoggingDecorator]

          Task.sleep(5 seconds).runSyncUnsafe()

          rorInstance.engines.value.impersonatorsEngine should be(Option.empty)
          rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() should be(TestConfig.Invalidated(testConfig1, 3 seconds))
        }
      }
      "can be invalidated by user" in {
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
        mockCoreFactory(coreFactory, testConfig1, mockEnabledAccessControl)

        val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(10 seconds))

        val result = readonlyRest.start().runSyncUnsafe()

        val rorInstance = result.value
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
          .forceReloadTestConfigEngine(testConfig1, 1 minute)(newRequestId())
          .runSyncUnsafe()

        testEngineReloadResult.value shouldBe a[TestConfig.Present]
        rorInstance.engines.value.impersonatorsEngine.value.core.accessControl shouldBe a[AccessControlLoggingDecorator]
        rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe()  shouldBe a[TestConfig.Present]

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
        rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() should be(TestConfig.Invalidated(recent = testConfig1, configuredTtl = 1 minute))
      }
      "should return error for invalidation" when {
        "cannot save invalidation timestamp in index" in {
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
          mockCoreFactory(coreFactory, testConfig1, mockEnabledAccessControl)

          val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(10 seconds))

          val result = readonlyRest.start().runSyncUnsafe()

          val rorInstance = result.value
          rorInstance.engines.value.impersonatorsEngine.value.core.accessControl shouldBe a[AccessControlLoggingDecorator]
          rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() should be(
            TestConfig.Present(
              config = RorConfig.disabled,
              rawConfig = testConfig1,
              configuredTtl = FiniteDuration(100, TimeUnit.SECONDS),
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
          rorInstance.currentTestConfig()(newRequestId()).runSyncUnsafe() should be(TestConfig.Invalidated(testConfig1, 100 seconds))
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
                              accessControlMock: AccessControl = mockEnabledAccessControl): CoreFactory = {
    mockCoreFactory(mockedCoreFactory, rorConfigFromResource(resourceFileName), accessControlMock)
  }

  private def mockCoreFactory(mockedCoreFactory: CoreFactory,
                              rawRorConfig: RawRorConfig,
                              accessControlMock: AccessControl): CoreFactory = {
    (mockedCoreFactory.createCoreFrom _)
      .expects(where {
        (config: RawRorConfig, _, _, _, _) => config == rawRorConfig
      })
      .once()
      .returns(Task.now(Right(Core(accessControlMock, RorConfig.disabled))))
    mockedCoreFactory
  }

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

  private implicit def toRefined(fd: FiniteDuration): FiniteDuration Refined Positive = fd.toRefinedPositiveUnsafe

  private def newRequestId() = RequestId(UUID.randomUUID().toString)

  private abstract class EnabledAcl extends AccessControl

  private abstract class DisabledAcl extends AccessControl
}
