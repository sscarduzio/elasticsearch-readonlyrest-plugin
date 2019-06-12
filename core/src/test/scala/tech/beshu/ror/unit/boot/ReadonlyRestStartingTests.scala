package tech.beshu.ror.unit.boot

import java.time.Clock

import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.{Inside, WordSpec}
import tech.beshu.ror.acl.factory.{CoreFactory, CoreSettings}
import tech.beshu.ror.acl.{Acl, AclStaticContext}
import tech.beshu.ror.boot.ReadonlyRest
import tech.beshu.ror.configuration.SslConfiguration.{KeyPass, KeystorePassword, SSLSettingsMalformedException}
import tech.beshu.ror.configuration.{RawRorConfig, RorSsl, SslConfiguration}
import tech.beshu.ror.es.IndexJsonContentManager.CannotReachContentSource
import tech.beshu.ror.es.{AuditSink, IndexJsonContentManager}
import tech.beshu.ror.utils.TestsUtils.{getResourceContent, getResourcePath, rorConfigFromResource}
import tech.beshu.ror.utils.{EnvVarsProvider, OsEnvVarsProvider}

import scala.collection.JavaConverters._

class ReadonlyRestStartingTests extends WordSpec with Inside with MockFactory with Eventually {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(10, Seconds)), interval = scaled(Span(100, Millis)))

  "A ReadonlyREST core" should {
    "be loaded from file" when {
      "index is not available but file config is provided" in {
        val mockedIndexJsonContentManager = mock[IndexJsonContentManager]
        (mockedIndexJsonContentManager.sourceOf _)
          .expects(".readonlyrest", "settings", "1")
          .once()
          .returns(Task.now(Left(CannotReachContentSource)))

        val coreFactory = mock[CoreFactory]
        (coreFactory.createCoreFrom _)
          .expects(where {
            (config: RawRorConfig, _) => config == rorConfigFromResource("/boot_tests/no_index_config_file_config_provided/readonlyrest.yml")
          })
          .once()
          .returns(Task.now(Right(CoreSettings(mock[Acl], mock[AclStaticContext], None))))

        val result = readonlyRestBoot(coreFactory)
          .start(
            getResourcePath("/boot_tests/no_index_config_file_config_provided/"),
            mock[AuditSink],
            mockedIndexJsonContentManager
          )
          .runSyncUnsafe()

        inside(result) { case Right(instance) =>
          eventually {
            instance.engine.isDefined should be(true)
          }
        }
      }
      "file loading is forced in elasticsearch.yml" in {
        val coreFactory = mock[CoreFactory]
        (coreFactory.createCoreFrom _)
          .expects(where {
            (config: RawRorConfig, _) => config == rorConfigFromResource("/boot_tests/forced_file_loading/readonlyrest.yml")
          })
          .once()
          .returns(Task.now(Right(CoreSettings(mock[Acl], mock[AclStaticContext], None))))

        val result = readonlyRestBoot(coreFactory)
          .start(
            getResourcePath("/boot_tests/forced_file_loading/"),
            mock[AuditSink],
            mock[IndexJsonContentManager]
          )
          .runSyncUnsafe()

        inside(result) { case Right(instance) =>
          instance.engine.isDefined should be(true)
        }
      }
    }
    "be loaded from index" when {
      "index is available and file config is provided" in {
        val resourcesPath = "/boot_tests/index_config_available_file_config_provided/"
        val indexConfigFile = "readonlyrest_index.yml"
        val mockedIndexJsonContentManager = mock[IndexJsonContentManager]
        (mockedIndexJsonContentManager.sourceOf _)
          .expects(".readonlyrest", "settings", "1")
          .once()
          .returns(Task.now(Right(
            Map("settings" -> getResourceContent(resourcesPath + indexConfigFile).asInstanceOf[Any]).asJava
          )))

        val coreFactory = mock[CoreFactory]
        (coreFactory.createCoreFrom _)
          .expects(where {
            (config: RawRorConfig, _) => config == rorConfigFromResource(resourcesPath + indexConfigFile)
          })
          .once()
          .returns(Task.now(Right(CoreSettings(mock[Acl], mock[AclStaticContext], None))))

        val result = readonlyRestBoot(coreFactory)
          .start(
            getResourcePath(resourcesPath),
            mock[AuditSink],
            mockedIndexJsonContentManager
          )
          .runSyncUnsafe()

        inside(result) { case Right(instance) =>
          eventually {
            instance.engine.isDefined should be(true)
          }
        }
      }
      "index is available and file config is not provided" in {
        val resourcesPath = "/boot_tests/index_config_available_file_config_not_provided/"
        val indexConfigFile = "readonlyrest_index.yml"
        val mockedIndexJsonContentManager = mock[IndexJsonContentManager]
        (mockedIndexJsonContentManager.sourceOf _)
          .expects(".readonlyrest", "settings", "1")
          .once()
          .returns(Task.now(Right(
            Map("settings" -> getResourceContent(resourcesPath + indexConfigFile).asInstanceOf[Any]).asJava
          )))

        val coreFactory = mock[CoreFactory]
        (coreFactory.createCoreFrom _)
          .expects(where {
            (config: RawRorConfig, _) => config == rorConfigFromResource(resourcesPath + indexConfigFile)
          })
          .once()
          .returns(Task.now(Right(CoreSettings(mock[Acl], mock[AclStaticContext], None))))

        val result = readonlyRestBoot(coreFactory)
          .start(
            getResourcePath(resourcesPath),
            mock[AuditSink],
            mockedIndexJsonContentManager
          )
          .runSyncUnsafe()

        inside(result) { case Right(instance) =>
          eventually {
            instance.engine.isDefined should be(true)
          }
        }
      }
    }
  }

  "A ReadonlyREST ES API SSL settings" should {
    "be loaded from elasticsearch config file" in {
      val ssl = RorSsl.load(getResourcePath("/boot_tests/es_api_ssl_settings_in_elasticsearch_config/")).runSyncUnsafe()
      inside(ssl.externalSsl) { case Some(SslConfiguration(file, Some(keystorePassword), Some(keyPass), None, allowedProtocols, allowedCiphers, false)) =>
        file.getName should be("keystore.jks")
        keystorePassword should be(KeystorePassword("readonlyrest1"))
        keyPass should be(KeyPass("readonlyrest2"))
        allowedProtocols.asScala should be(Set.empty)
        allowedCiphers.asScala should be(Set.empty)
      }
      ssl.interNodeSsl should be(None)
    }
    "be loaded from readonlyrest config file" when {
      "elasticsearch config file doesn't contain ROR ssl section" in {
        val ssl = RorSsl.load(getResourcePath("/boot_tests/es_api_ssl_settings_in_readonlyrest_config/")).runSyncUnsafe()
        inside(ssl.externalSsl) { case Some(SslConfiguration(file, Some(keystorePassword), Some(keyPass), None, allowedProtocols, allowedCiphers, false)) =>
          file.getName should be("keystore.jks")
          keystorePassword should be(KeystorePassword("readonlyrest1"))
          keyPass should be(KeyPass("readonlyrest2"))
          allowedProtocols.asScala should be(Set.empty)
          allowedCiphers.asScala should be(Set.empty)
        }
        ssl.interNodeSsl should be(None)
      }
    }
    "be disabled" when {
      "no ssl section is provided" in {
        val ssl = RorSsl.load(getResourcePath("/boot_tests/no_es_api_ssl_settings/")).runSyncUnsafe()
        ssl.externalSsl should be (None)
        ssl.interNodeSsl should be (None)
      }
      "it's disabled by proper settings" in {
        val ssl = RorSsl.load(getResourcePath("/boot_tests/es_api_ssl_settings_disabled/")).runSyncUnsafe()
        ssl.externalSsl should be (None)
        ssl.interNodeSsl should be (None)
      }
    }
    "not be able to load" when {
      "SSL settings are malformed" when {
        "keystore_file entry is missing" in {
          intercept[SSLSettingsMalformedException] {
            RorSsl.load(getResourcePath("/boot_tests/es_api_ssl_settings_malformed/")).runSyncUnsafe()
          }
        }
      }
    }
  }

  "A ReadonlyREST internode SSL settings" should {
    "be loaded from elasticsearch config file" in {
      val ssl = RorSsl.load(getResourcePath("/boot_tests/internode_ssl_settings_in_elasticsearch_config/")).runSyncUnsafe()
      inside(ssl.interNodeSsl) { case Some(SslConfiguration(file, Some(keystorePassword), Some(keyPass), None, allowedProtocols, allowedCiphers, false)) =>
        file.getName should be("keystore.jks")
        keystorePassword should be(KeystorePassword("readonlyrest1"))
        keyPass should be(KeyPass("readonlyrest2"))
        allowedProtocols.asScala should be(Set.empty)
        allowedCiphers.asScala should be(Set.empty)
      }
      ssl.externalSsl should be(None)
    }
    "be loaded from readonlyrest config file" when {
      "elasticsearch config file doesn't contain ROR ssl section" in {
        val ssl = RorSsl.load(getResourcePath("/boot_tests/internode_ssl_settings_in_readonlyrest_config/")).runSyncUnsafe()
        inside(ssl.interNodeSsl) { case Some(SslConfiguration(file, Some(keystorePassword), Some(keyPass), None, allowedProtocols, allowedCiphers, false)) =>
          file.getName should be("keystore.jks")
          keystorePassword should be(KeystorePassword("readonlyrest1"))
          keyPass should be(KeyPass("readonlyrest2"))
          allowedProtocols.asScala should be(Set.empty)
          allowedCiphers.asScala should be(Set.empty)
        }
        ssl.externalSsl should be(None)
      }
    }
    "be disabled" when {
      "no ssl section is provided" in {
        val ssl = RorSsl.load(getResourcePath("/boot_tests/no_internode_ssl_settings/")).runSyncUnsafe()
        ssl.externalSsl should be (None)
        ssl.interNodeSsl should be (None)
      }
      "it's disabled by proper settings" in {
        val ssl = RorSsl.load(getResourcePath("/boot_tests/internode_ssl_settings_disabled/")).runSyncUnsafe()
        ssl.externalSsl should be (None)
        ssl.interNodeSsl should be (None)
      }
    }
    "not be able to load" when {
      "SSL settings are malformed" when {
        "keystore_file entry is missing" in {
          intercept[SSLSettingsMalformedException] {
            RorSsl.load(getResourcePath("/boot_tests/internode_ssl_settings_malformed/")).runSyncUnsafe()
          }
        }
      }
    }
  }

  private def readonlyRestBoot(factory: CoreFactory) = {
    new ReadonlyRest {
      override implicit protected val clock: Clock = Clock.systemUTC()
      override protected val envVarsProvider: EnvVarsProvider = OsEnvVarsProvider

      override protected def coreFactory: CoreFactory = factory
    }
  }

}
