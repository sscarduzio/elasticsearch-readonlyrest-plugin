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
import eu.timepit.refined.auto._
import monix.eval.Task
import monix.execution.Scheduler
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
import tech.beshu.ror.accesscontrol.blocks.mocks.{MutableMocksProviderWithCachePerRequest, NoOpMocksProvider}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.{CoreFactory, CoreSettings}
import tech.beshu.ror.accesscontrol.logging.AccessControlLoggingDecorator
import tech.beshu.ror.boot.ReadonlyRest.AuditSinkCreator
import tech.beshu.ror.boot.RorInstance.RawConfigReloadError
import tech.beshu.ror.boot.RorInstance.RawConfigReloadError.ReloadingFailed
import tech.beshu.ror.boot.{ReadonlyRest, StartingFailure}
import tech.beshu.ror.configuration.SslConfiguration._
import tech.beshu.ror.configuration.{IndexConfigManager, MalformedSettings, RawRorConfig, RorSsl}
import tech.beshu.ror.es.IndexJsonContentService.{CannotReachContentSource, ContentNotFound, WriteError}
import tech.beshu.ror.es.{AuditSinkService, IndexJsonContentService}
import tech.beshu.ror.providers.{EnvVarsProvider, OsEnvVarsProvider, PropertiesProvider}
import tech.beshu.ror.utils.TestsPropertiesProvider
import tech.beshu.ror.utils.TestsUtils.{getResourceContent, getResourcePath, rorConfigFromResource, _}

import java.nio.file.Path
import java.time.Clock
import java.util.UUID
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.language.postfixOps

class ReadonlyRestStartingTests
  extends AnyWordSpec
    with Inside with OptionValues with EitherValues
    with MockFactory with Eventually {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(15, Seconds)), interval = scaled(Span(100, Millis)))
  implicit private val envVarsProvider: EnvVarsProvider = OsEnvVarsProvider

  // todo: resource cleaning needed (shutdown of started ROR)
  "A ReadonlyREST core" should {
    "be loaded from file" when {
      "index is not available but file config is provided" in {
        val mockedIndexJsonContentManager = mock[IndexJsonContentService]
        (mockedIndexJsonContentManager.sourceOf _)
          .expects(fullIndexName(".readonlyrest"), "1")
          .repeated(5)
          .returns(Task.now(Left(CannotReachContentSource)))

        val coreFactory = mockCoreFactory(mock[CoreFactory], "/boot_tests/no_index_config_file_config_provided/readonlyrest.yml")
        val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, "/boot_tests/no_index_config_file_config_provided/")

        val result = readonlyRest.start().runSyncUnsafe()

        val acl = result.right.value.engines.value.mainEngine.accessControl
        acl shouldBe a[AccessControlLoggingDecorator]
        acl.asInstanceOf[AccessControlLoggingDecorator].underlying shouldBe a[EnabledAcl]
      }
      "file loading is forced in elasticsearch.yml" in {
        val coreFactory = mockCoreFactory(mock[CoreFactory], "/boot_tests/forced_file_loading/readonlyrest.yml")
        val readonlyRest = readonlyRestBoot(coreFactory, mock[IndexJsonContentService], "/boot_tests/forced_file_loading/")

        val result = readonlyRest.start().runSyncUnsafe()

        val acl = result.right.value.engines.value.mainEngine.accessControl
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

        val coreFactory = mockCoreFactory(mock[CoreFactory], resourcesPath + indexConfigFile)
        val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath)

        val result = readonlyRest.start().runSyncUnsafe()

        val acl = result.right.value.engines.value.mainEngine.accessControl
        acl shouldBe a[AccessControlLoggingDecorator]
        acl.asInstanceOf[AccessControlLoggingDecorator].underlying shouldBe a[EnabledAcl]
      }
      "index is available and file config is not provided" in {
        val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
        val indexConfigFile = "readonlyrest_index.yml"

        val mockedIndexJsonContentManager = mock[IndexJsonContentService]
        mockIndexJsonContentManagerSourceOfCall(mockedIndexJsonContentManager, resourcesPath + indexConfigFile)

        val coreFactory = mockCoreFactory(mock[CoreFactory], resourcesPath + indexConfigFile)

        val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath)

        val result = readonlyRest.start().runSyncUnsafe()

        val acl = result.right.value.engines.value.mainEngine.accessControl
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

        val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(0 seconds))

        val result = readonlyRest.start().runSyncUnsafe()

        val instance = result.right.value
        val mainEngine = instance.engines.value.mainEngine
        mainEngine.accessControl shouldBe a[AccessControlLoggingDecorator]
        mainEngine.accessControl.asInstanceOf[AccessControlLoggingDecorator].underlying shouldBe a[EnabledAcl]

        implicit val requestId: RequestId = RequestId(UUID.randomUUID().toString)
        val reload1Result = instance
          .forceReloadAndSave(rorConfigFromResource(resourcesPath + newIndexConfigFile))
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

        val coreFactory = mock[CoreFactory]
        mockCoreFactory(coreFactory, resourcesPath + initialIndexConfigFile)
        mockCoreFactory(coreFactory, resourcesPath + firstNewIndexConfigFile)
        mockCoreFactory(coreFactory, resourcesPath + secondNewIndexConfigFile,
          createCoreResult =
            Task
              .sleep(100 millis)
              .map(_ => Right(CoreSettings(mockEnabledAccessControl, None))) // very long creation
        )
        mockIndexJsonContentManagerSaveCall(
          mockedIndexJsonContentManager,
          resourcesPath + firstNewIndexConfigFile,
          Task.sleep(500 millis).map(_ => Right(())) // very long saving
        )

        val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(0 seconds))

        val result = readonlyRest.start().runSyncUnsafe()

        val instance = result.right.value
        val acl = eventually {
          instance.engines.value.mainEngine.accessControl
        }

        val results = Task
          .gather(List(
            instance
              .forceReloadAndSave(rorConfigFromResource(resourcesPath + firstNewIndexConfigFile))(RequestId(UUID.randomUUID().toString))
              .map { result =>
                // schedule after first finish
                mockIndexJsonContentManagerSaveCall(mockedIndexJsonContentManager, resourcesPath + secondNewIndexConfigFile)
                result
              },
            Task
              .sleep(200 millis)
              .flatMap { _ =>
                instance.forceReloadAndSave(rorConfigFromResource(resourcesPath + secondNewIndexConfigFile))(RequestId(UUID.randomUUID().toString))
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
      mockCoreFactory(coreFactory, resourcesPath + originIndexConfigFile, mockDisabledAccessControl)

      mockIndexJsonContentManagerSourceOfCall(mockedIndexJsonContentManager, resourcesPath + updatedIndexConfigFile)
      mockCoreFactory(coreFactory, resourcesPath + updatedIndexConfigFile)

      val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(2 seconds))

      val result = readonlyRest.start().flatMap { result =>
        val acl = result.right.value.engines.value.mainEngine.accessControl
        acl shouldBe a[AccessControlLoggingDecorator]
        acl.asInstanceOf[AccessControlLoggingDecorator].underlying shouldBe a[DisabledAcl]

        Task
          .sleep(4 seconds)
          .map(_ => result)
      }
        .runSyncUnsafe()

      val acl = result.right.value.engines.value.mainEngine.accessControl
      acl shouldBe a[AccessControlLoggingDecorator]
      acl.asInstanceOf[AccessControlLoggingDecorator].underlying shouldBe a[EnabledAcl]
    }
    "support test engine" which {
      "can be loaded" when {
        "no test engine is loaded" in {
          val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
          val indexConfigFile = "readonlyrest_index.yml"

          val mockedIndexJsonContentManager = mock[IndexJsonContentService]
          mockIndexJsonContentManagerSourceOfCall(mockedIndexJsonContentManager, resourcesPath + indexConfigFile)

          val coreFactory = mock[CoreFactory]
          mockCoreFactory(coreFactory, resourcesPath + indexConfigFile)
          mockCoreFactory(coreFactory, testConfig1, mockEnabledAccessControl)

          val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(0 seconds))

          val result = readonlyRest.start().runSyncUnsafe()

          val rorInstance = result.right.value
          rorInstance.engines.value.impersonatorsEngine should be(Option.empty)

          implicit val requestId: RequestId = RequestId("test")
          val testEngineReloadResult = rorInstance
            .forceReloadImpersonatorsEngine(testConfig1, 1 minute)
            .runSyncUnsafe()

          testEngineReloadResult should be(Right(()))
          rorInstance.engines.value.impersonatorsEngine.value.accessControl shouldBe a[AccessControlLoggingDecorator]
        }
        "test engine is loaded but new different config is being loaded" in {
          val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
          val indexConfigFile = "readonlyrest_index.yml"

          val mockedIndexJsonContentManager = mock[IndexJsonContentService]
          mockIndexJsonContentManagerSourceOfCall(mockedIndexJsonContentManager, resourcesPath + indexConfigFile)

          val coreFactory = mock[CoreFactory]
          mockCoreFactory(coreFactory, resourcesPath + indexConfigFile)
          mockCoreFactory(coreFactory, testConfig1, mockEnabledAccessControl)
          mockCoreFactory(coreFactory, testConfig2, mockEnabledAccessControl)

          val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(0 seconds))

          val result = readonlyRest.start().runSyncUnsafe()

          val rorInstance = result.right.value
          rorInstance.engines.value.impersonatorsEngine should be(Option.empty)

          implicit val requestId: RequestId = RequestId("test")
          val testEngineReloadResult1stAttempt = rorInstance
            .forceReloadImpersonatorsEngine(testConfig1, 1 minute)
            .runSyncUnsafe()

          testEngineReloadResult1stAttempt should be(Right(()))
          rorInstance.engines.value.impersonatorsEngine.value.accessControl shouldBe a[AccessControlLoggingDecorator]

          val testEngineReloadResult2ndAttempt = rorInstance
            .forceReloadImpersonatorsEngine(testConfig2, 1 minute)
            .runSyncUnsafe()

          testEngineReloadResult2ndAttempt should be(Right(()))
          rorInstance.engines.value.impersonatorsEngine.value.accessControl shouldBe a[AccessControlLoggingDecorator]
        }
      }
      "cannot be loaded" when {
        "config is malformed" in {
          val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
          val indexConfigFile = "readonlyrest_index.yml"

          val mockedIndexJsonContentManager = mock[IndexJsonContentService]
          mockIndexJsonContentManagerSourceOfCall(mockedIndexJsonContentManager, resourcesPath + indexConfigFile)

          val coreFactory = mock[CoreFactory]
          mockCoreFactory(coreFactory, resourcesPath + indexConfigFile)
          mockFailedCoreFactory(coreFactory, testConfigMalformed)

          val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(0 seconds))

          val result = readonlyRest.start().runSyncUnsafe()
          val rorInstance = result.right.value
          rorInstance.engines.value.impersonatorsEngine should be(Option.empty)

          implicit val requestId: RequestId = RequestId("test")
          val testEngineReloadResult = rorInstance
            .forceReloadImpersonatorsEngine(testConfigMalformed, 1 minute)
            .runSyncUnsafe()

          testEngineReloadResult should be(Left(ReloadingFailed(StartingFailure("Errors:\nfailed"))))
          rorInstance.engines.value.impersonatorsEngine should be(Option.empty)
        }
        "the same config is trying to be loaded" in {
          val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
          val indexConfigFile = "readonlyrest_index.yml"

          val mockedIndexJsonContentManager = mock[IndexJsonContentService]
          mockIndexJsonContentManagerSourceOfCall(mockedIndexJsonContentManager, resourcesPath + indexConfigFile)

          val coreFactory = mock[CoreFactory]
          mockCoreFactory(coreFactory, resourcesPath + indexConfigFile)
          mockCoreFactory(coreFactory, testConfig1, mockEnabledAccessControl)

          val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(0 seconds))

          val result = readonlyRest.start().runSyncUnsafe()

          val rorInstance = result.right.value
          rorInstance.engines.value.impersonatorsEngine should be(Option.empty)

          implicit val requestId: RequestId = RequestId("test")
          val testEngineReloadResult1stAttempt = rorInstance
            .forceReloadImpersonatorsEngine(testConfig1, 1 minute)
            .runSyncUnsafe()

          testEngineReloadResult1stAttempt should be(Right(()))
          rorInstance.engines.value.impersonatorsEngine.value.accessControl shouldBe a[AccessControlLoggingDecorator]

          val testEngineReloadResult2ndAttempt = rorInstance
            .forceReloadImpersonatorsEngine(testConfig1, 1 minute)
            .runSyncUnsafe()

          testEngineReloadResult2ndAttempt should be(Left(RawConfigReloadError.ConfigUpToDate(testConfig1)))
          rorInstance.engines.value.impersonatorsEngine.value.accessControl shouldBe a[AccessControlLoggingDecorator]
        }
      }
      "should be automatically unloaded" when {
        "its TTL is reached" in {
          val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
          val indexConfigFile = "readonlyrest_index.yml"

          val mockedIndexJsonContentManager = mock[IndexJsonContentService]
          mockIndexJsonContentManagerSourceOfCall(mockedIndexJsonContentManager, resourcesPath + indexConfigFile)

          val coreFactory = mock[CoreFactory]
          mockCoreFactory(coreFactory, resourcesPath + indexConfigFile)
          mockCoreFactory(coreFactory, testConfig1, mockEnabledAccessControl)

          val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(0 seconds))

          val result = readonlyRest.start().runSyncUnsafe()

          val rorInstance = result.right.value
          rorInstance.engines.value.impersonatorsEngine should be(Option.empty)

          implicit val requestId: RequestId = RequestId("test")
          val testEngineReloadResult = rorInstance
            .forceReloadImpersonatorsEngine(testConfig1, 3 seconds)
            .runSyncUnsafe()

          testEngineReloadResult should be(Right(()))
          rorInstance.engines.value.impersonatorsEngine.value.accessControl shouldBe a[AccessControlLoggingDecorator]

          Task.sleep(5 seconds).runSyncUnsafe()

          rorInstance.engines.value.impersonatorsEngine should be(Option.empty)
        }
      }
      "can be invalidated by user" in {
        val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
        val indexConfigFile = "readonlyrest_index.yml"

        val mockedIndexJsonContentManager = mock[IndexJsonContentService]
        mockIndexJsonContentManagerSourceOfCall(mockedIndexJsonContentManager, resourcesPath + indexConfigFile)

        val coreFactory = mock[CoreFactory]
        mockCoreFactory(coreFactory, resourcesPath + indexConfigFile)
        mockCoreFactory(coreFactory, testConfig1, mockEnabledAccessControl)

        val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath, refreshInterval = Some(0 seconds))

        val result = readonlyRest.start().runSyncUnsafe()

        val rorInstance = result.right.value
        rorInstance.engines.value.impersonatorsEngine should be(Option.empty)

        implicit val requestId: RequestId = RequestId("test")
        val testEngineReloadResult = rorInstance
          .forceReloadImpersonatorsEngine(testConfig1, 1 minute)
          .runSyncUnsafe()

        testEngineReloadResult should be(Right(()))
        rorInstance.engines.value.impersonatorsEngine.value.accessControl shouldBe a[AccessControlLoggingDecorator]

        rorInstance
          .invalidateImpersonationEngine()
          .runSyncUnsafe()

        rorInstance.engines.value.impersonatorsEngine should be(Option.empty)
      }
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
        val readonlyRest = readonlyRestBoot(coreFactory, mock[IndexJsonContentService], "/boot_tests/forced_file_loading_bad_config/")

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

        val coreFactory = mockFailedCoreFactory(mock[CoreFactory], resourcesPath + indexConfigFile)
        val readonlyRest = readonlyRestBoot(coreFactory, mockedIndexJsonContentManager, resourcesPath)

        val result = readonlyRest.start().runSyncUnsafe()

        inside(result) { case Left(failure) =>
          failure.message shouldBe "Errors:\nfailed"
        }
      }
    }
  }

  "A ReadonlyREST ES API SSL settings" should {

    implicit val propertiesProvider = TestsPropertiesProvider.default

    "be loaded from elasticsearch config file" when {
      "all properties contain at least one non-digit" in {
        val ssl = RorSsl.load(getResourcePath("/boot_tests/es_api_ssl_settings_in_elasticsearch_config/")).runSyncUnsafe().right.get
        inside(ssl.externalSsl) {
          case Some(ExternalSslConfiguration(keystoreFile, Some(keystorePassword), Some(keyPass), None, Some(truststoreFile), Some(truststorePassword), allowedProtocols, allowedCiphers, clientAuthenticationEnabled)) =>
            keystoreFile.value.getName should be("keystore.jks")
            keystorePassword should be(KeystorePassword("readonlyrest1"))
            keyPass should be(KeyPass("readonlyrest2"))
            truststoreFile.value.getName should be("truststore.jks")
            truststorePassword should be(TruststorePassword("readonlyrest3"))
            allowedProtocols should be(Set.empty)
            allowedCiphers should be(Set.empty)
            clientAuthenticationEnabled should be(false)
        }
        ssl.interNodeSsl should be(None)
      }
      "some properties contains only digits" in {
        val ssl = RorSsl.load(getResourcePath("/boot_tests/es_api_ssl_settings_in_elasticsearch_config_only_digits/")).runSyncUnsafe().right.get
        inside(ssl.externalSsl) {
          case Some(ExternalSslConfiguration(keystoreFile, Some(keystorePassword), Some(keyPass), None, Some(truststoreFile), Some(truststorePassword), allowedProtocols, allowedCiphers, clientAuthenticationEnabled)) =>
            keystoreFile.value.getName should be("keystore.jks")
            keystorePassword should be(KeystorePassword("123456"))
            keyPass should be(KeyPass("12"))
            truststoreFile.value.getName should be("truststore.jks")
            truststorePassword should be(TruststorePassword("1234"))
            allowedProtocols should be(Set.empty)
            allowedCiphers should be(Set.empty)
            clientAuthenticationEnabled should be(false)
        }
        ssl.interNodeSsl should be(None)
      }
    }
    "be loaded from readonlyrest config file" when {
      "elasticsearch config file doesn't contain ROR ssl section" in {
        val ssl = RorSsl.load(getResourcePath("/boot_tests/es_api_ssl_settings_in_readonlyrest_config/")).runSyncUnsafe().right.get
        inside(ssl.externalSsl) {
          case Some(ExternalSslConfiguration(keystoreFile, Some(keystorePassword), Some(keyPass), None, Some(truststoreFile), Some(truststorePassword), allowedProtocols, allowedCiphers, clientAuthenticationEnabled)) =>
            keystoreFile.value.getName should be("keystore.jks")
            keystorePassword should be(KeystorePassword("readonlyrest1"))
            keyPass should be(KeyPass("readonlyrest2"))
            truststoreFile.value.getName should be("truststore.jks")
            truststorePassword should be(TruststorePassword("readonlyrest3"))
            allowedProtocols should be(Set.empty)
            allowedCiphers should be(Set.empty)
            clientAuthenticationEnabled should be(false)
        }
        ssl.interNodeSsl should be(None)
      }
    }
    "be disabled" when {
      "no ssl section is provided" in {
        val ssl = RorSsl.load(getResourcePath("/boot_tests/no_es_api_ssl_settings/")).runSyncUnsafe().right.get
        ssl.externalSsl should be(None)
        ssl.interNodeSsl should be(None)
      }
      "it's disabled by proper settings" in {
        val ssl = RorSsl.load(getResourcePath("/boot_tests/es_api_ssl_settings_disabled/")).runSyncUnsafe().right.get
        ssl.externalSsl should be(None)
        ssl.interNodeSsl should be(None)
      }
    }
    "not be able to load" when {
      "SSL settings are malformed" when {
        "keystore_file entry is missing" in {
          RorSsl.load(getResourcePath("/boot_tests/es_api_ssl_settings_malformed/")).runSyncUnsafe() shouldBe Left {
            MalformedSettings("Invalid ROR SSL configuration")
          }
        }
      }
      "file content is not valid yaml" in {
        val error = RorSsl.load(getResourcePath("/boot_tests/es_api_ssl_settings_file_invalid_yaml/")).runSyncUnsafe().left.get
        error.message should startWith("Cannot parse file")
      }
    }
  }

  "A ReadonlyREST internode SSL settings" should {
    implicit val propertiesProvider = TestsPropertiesProvider.default

    "be loaded from elasticsearch config file" in {
      val ssl = RorSsl.load(getResourcePath("/boot_tests/internode_ssl_settings_in_elasticsearch_config/")).runSyncUnsafe().right.get
      inside(ssl.interNodeSsl) {
        case Some(InternodeSslConfiguration(keystoreFile, Some(keystorePassword), Some(keyPass), None, truststoreFile, truststorePassword, allowedProtocols, allowedCiphers, certificateVerificationEnabled)) =>
          keystoreFile.value.getName should be("keystore.jks")
          keystorePassword should be(KeystorePassword("readonlyrest1"))
          keyPass should be(KeyPass("readonlyrest2"))
          truststoreFile should be(None)
          truststorePassword should be(None)
          allowedProtocols should be(Set.empty)
          allowedCiphers should be(Set.empty)
          certificateVerificationEnabled should be(true)
      }
      ssl.externalSsl should be(None)
    }
    "be loaded from readonlyrest config file" when {
      "elasticsearch config file doesn't contain ROR ssl section" in {
        val ssl = RorSsl.load(getResourcePath("/boot_tests/internode_ssl_settings_in_readonlyrest_config/")).runSyncUnsafe().right.get
        inside(ssl.interNodeSsl) {
          case Some(InternodeSslConfiguration(keystoreFile, Some(keystorePassword), Some(keyPass), None, Some(truststoreFile), Some(truststorePassword), allowedProtocols, allowedCiphers, certificateVerificationEnabled)) =>
            keystoreFile.value.getName should be("keystore.jks")
            keystorePassword should be(KeystorePassword("readonlyrest1"))
            keyPass should be(KeyPass("readonlyrest2"))
            truststoreFile.value.getName should be("truststore.jks")
            truststorePassword should be(TruststorePassword("readonlyrest3"))
            allowedProtocols should be(Set.empty)
            allowedCiphers should be(Set.empty)
            certificateVerificationEnabled should be(true)
        }
        ssl.externalSsl should be(None)
      }
    }
    "be disabled" when {
      "no ssl section is provided" in {
        val ssl = RorSsl.load(getResourcePath("/boot_tests/no_internode_ssl_settings/")).runSyncUnsafe().right.get
        ssl.externalSsl should be(None)
        ssl.interNodeSsl should be(None)
      }
      "it's disabled by proper settings" in {
        val ssl = RorSsl.load(getResourcePath("/boot_tests/internode_ssl_settings_disabled/")).runSyncUnsafe().right.get
        ssl.externalSsl should be(None)
        ssl.interNodeSsl should be(None)
      }
    }
    "not be able to load" when {
      "SSL settings are malformed" when {
        "keystore_file entry is missing" in {
          RorSsl.load(getResourcePath("/boot_tests/internode_ssl_settings_malformed/")).runSyncUnsafe() shouldBe Left {
            MalformedSettings("Invalid ROR SSL configuration")
          }
        }
      }
    }
  }

  private def readonlyRestBoot(factory: CoreFactory,
                               indexJsonContentService: IndexJsonContentService,
                               configPath: String,
                               refreshInterval: Option[FiniteDuration] = None) = {
    new ReadonlyRest {

      override implicit protected val clock: Clock = Clock.systemUTC()
      override implicit protected val scheduler: Scheduler = monix.execution.Scheduler.global

      override val esConfigPath = getResourcePath(configPath)
      override val indexConfigManager = new IndexConfigManager(indexJsonContentService)
      override val mocksProvider = new MutableMocksProviderWithCachePerRequest(NoOpMocksProvider)(scheduler, clock)

      override protected def auditSinkCreator: AuditSinkCreator = _ => mock[AuditSinkService]

      override protected val envVarsProvider: EnvVarsProvider = OsEnvVarsProvider

      override protected def coreFactory: CoreFactory = factory

      override implicit protected def propertiesProvider: PropertiesProvider =
        TestsPropertiesProvider.usingMap(
          mapWithIntervalFrom(refreshInterval) ++
            Map(
              "com.readonlyrest.settings.loading.delay" -> "0"
            )
        )

      private def mapWithIntervalFrom(refreshInterval: Option[FiniteDuration]) =
        refreshInterval
          .map(i => "com.readonlyrest.settings.refresh.interval" -> i.toSeconds.toString)
          .toMap
    }
  }

  private def mockIndexJsonContentManagerSourceOfCall(mockedManager: IndexJsonContentService,
                                                      resourceFileName: String,
                                                      repeatedCount: Int = 1) = {
    (mockedManager.sourceOf _)
      .expects(fullIndexName(".readonlyrest"), "1")
      .repeated(repeatedCount)
      .returns(Task.now(Right(
        Map("settings" -> getResourceContent(resourceFileName).asInstanceOf[Any]).asJava
      )))
    mockedManager
  }

  private def mockIndexJsonContentManagerSaveCall(mockedManager: IndexJsonContentService,
                                                  resourceFileName: String,
                                                  saveResult: Task[Either[WriteError, Unit]] = Task.now(Right(()))) = {
    (mockedManager.saveContent _)
      .expects(fullIndexName(".readonlyrest"), "1", Map("settings" -> getResourceContent(resourceFileName)).asJava)
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
      .returns(Task.now(Right(CoreSettings(accessControlMock, None))))
    mockedCoreFactory
  }

  private def mockCoreFactory(mockedCoreFactory: CoreFactory,
                              resourceFileName: String,
                              createCoreResult: Task[Either[NonEmptyList[AclCreationError], CoreSettings]]) = {
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
      .returns(Task.now(Left(NonEmptyList.one(AclCreationError.GeneralReadonlyrestSettingsError(Message("failed"))))))
    mockedCoreFactory
  }

  private def mockEnabledAccessControl = {
    val mockedAccessControl = mock[EnabledAcl]
    (mockedAccessControl.staticContext _)
      .expects()
      .anyNumberOfTimes()
      .returns(mockAccessControlStaticContext)
    mockedAccessControl
  }

  private def mockDisabledAccessControl = {
    val mockedAccessControl = mock[DisabledAcl]
    (mockedAccessControl.staticContext _)
      .expects()
      .anyNumberOfTimes()
      .returns(mockAccessControlStaticContext)
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

  private abstract class EnabledAcl extends AccessControl

  private abstract class DisabledAcl extends AccessControl
}
