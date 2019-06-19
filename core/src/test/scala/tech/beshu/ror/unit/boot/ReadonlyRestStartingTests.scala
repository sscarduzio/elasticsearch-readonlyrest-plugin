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

import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.{Inside, WordSpec}
import tech.beshu.ror.acl.factory.{CoreFactory, CoreSettings}
import tech.beshu.ror.acl.{Acl, AclStaticContext, DisabledAclStaticContext}
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
    PatienceConfig(timeout = scaled(Span(15, Seconds)), interval = scaled(Span(100, Millis)))

  "A ReadonlyREST core" should {
    "be loaded from file" when {
      "index is not available but file config is provided" in {
        val mockedIndexJsonContentManager = mock[IndexJsonContentManager]
        (mockedIndexJsonContentManager.sourceOf _)
          .expects(".readonlyrest", "settings", "1")
          .once()
          .returns(Task.now(Left(CannotReachContentSource)))

        val coreFactory = mockCoreFactory(mock[CoreFactory], "/boot_tests/no_index_config_file_config_provided/readonlyrest.yml")

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
            instance.engine.get.context
          }
        }
      }
      "file loading is forced in elasticsearch.yml" in {
        val coreFactory = mockCoreFactory(mock[CoreFactory], "/boot_tests/forced_file_loading/readonlyrest.yml")

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
        mockIndexJsonContentManagerSourceOfCall(mockedIndexJsonContentManager, resourcesPath + indexConfigFile)

        val coreFactory = mockCoreFactory(mock[CoreFactory], resourcesPath + indexConfigFile)

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
        mockIndexJsonContentManagerSourceOfCall(mockedIndexJsonContentManager, resourcesPath + indexConfigFile)

        val coreFactory = mockCoreFactory(mock[CoreFactory], resourcesPath + indexConfigFile)

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
    "be reloaded if index config changes" in {
      val resourcesPath = "/boot_tests/index_config_reloading/"
      val originIndexConfigFile = "readonlyrest.yml"
      val updatedIndexConfigFile = "updated_readonlyrest.yml"

      val mockedIndexJsonContentManager = mock[IndexJsonContentManager]
      mockIndexJsonContentManagerSourceOfCall(mockedIndexJsonContentManager, resourcesPath + originIndexConfigFile)
      mockIndexJsonContentManagerSourceOfCall(mockedIndexJsonContentManager, resourcesPath + updatedIndexConfigFile)

      val coreFactory = mock[CoreFactory]
      mockCoreFactory(coreFactory, resourcesPath + originIndexConfigFile, DisabledAclStaticContext)
      mockCoreFactory(coreFactory, resourcesPath + updatedIndexConfigFile)

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
          instance.engine.get.context should be (DisabledAclStaticContext)
        }
        eventually {
          instance.engine.isDefined should be(true)
          instance.engine.get.context should not be DisabledAclStaticContext
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
        allowedProtocols should be(Set.empty)
        allowedCiphers should be(Set.empty)
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
          allowedProtocols should be(Set.empty)
          allowedCiphers should be(Set.empty)
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
        allowedProtocols should be(Set.empty)
        allowedCiphers should be(Set.empty)
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
          allowedProtocols should be(Set.empty)
          allowedCiphers should be(Set.empty)
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

  private def mockIndexJsonContentManagerSourceOfCall(mockedManager: IndexJsonContentManager, resourceFileName: String) = {
    (mockedManager.sourceOf _)
      .expects(".readonlyrest", "settings", "1")
      .once()
      .returns(Task.now(Right(
        Map("settings" -> getResourceContent(resourceFileName).asInstanceOf[Any]).asJava
      )))
    mockedManager
  }

  private def mockCoreFactory(mockedCoreFactory: CoreFactory,
                              resourceFileName: String,
                              aclStaticContext: AclStaticContext = mock[AclStaticContext]) = {
    (mockedCoreFactory.createCoreFrom _)
      .expects(where {
        (config: RawRorConfig, _) => config == rorConfigFromResource(resourceFileName)
      })
      .once()
      .returns(Task.now(Right(CoreSettings(mock[Acl], aclStaticContext, None))))
    mockedCoreFactory
  }

}
