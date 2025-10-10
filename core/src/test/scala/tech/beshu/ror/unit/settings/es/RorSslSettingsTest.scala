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

import better.files.File
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.SystemContext
import tech.beshu.ror.accesscontrol.domain.RorSettingsFile
import tech.beshu.ror.es.EsEnv
import tech.beshu.ror.settings.es.SslSettings.*
import tech.beshu.ror.settings.es.SslSettings.ServerCertificateSettings.{FileBasedSettings, KeystoreBasedSettings}
import tech.beshu.ror.settings.es.{MalformedSettings, RorSslSettings}
import tech.beshu.ror.utils.TestsPropertiesProvider
import tech.beshu.ror.utils.TestsUtils.{defaultEsVersionForTests, getResourcePath, testEsNodeSettings}

class RorSslSettingsTest
  extends AnyWordSpec with Inside {

  private implicit val systemContext: SystemContext = new SystemContext(
    propertiesProvider = TestsPropertiesProvider.default
  )

  "A ReadonlyREST ES API SSL settings" should {
    "be loaded from elasticsearch config file" when {
      "all properties contain at least one non-digit" in {
        val ssl = forceLoadRorSslSettings("/boot_tests/es_api_ssl_settings_in_elasticsearch_config")
        inside(ssl.externalSsl) {
          case Some(ExternalSslSettings(KeystoreBasedSettings(keystoreFile, Some(keystorePassword), None, Some(keyPass)), Some(ClientCertificateSettings.TruststoreBasedSettings(truststoreFile, Some(truststorePassword))), allowedProtocols, allowedCiphers, clientAuthenticationEnabled, FipsMode.NonFips)) =>
            keystoreFile.value.name should be("ror-keystore.jks")
            keystorePassword should be(KeystorePassword("readonlyrest1"))
            keyPass should be(KeyPass("readonlyrest2"))
            truststoreFile.value.name should be("ror-truststore.jks")
            truststorePassword should be(TruststorePassword("readonlyrest3"))
            allowedProtocols should be(Set.empty)
            allowedCiphers should be(Set.empty)
            clientAuthenticationEnabled should be(false)
        }
        ssl.internodeSsl should be(None)
      }
      "some properties contains only digits" in {
        val ssl = forceLoadRorSslSettings("/boot_tests/es_api_ssl_settings_in_elasticsearch_config_only_digits")
        inside(ssl.externalSsl) {
          case Some(ExternalSslSettings(KeystoreBasedSettings(keystoreFile, Some(keystorePassword), None, Some(keyPass)), Some(ClientCertificateSettings.TruststoreBasedSettings(truststoreFile, Some(truststorePassword))), allowedProtocols, allowedCiphers, clientAuthenticationEnabled, FipsMode.NonFips)) =>
            keystoreFile.value.name should be("ror-keystore.jks")
            keystorePassword should be(KeystorePassword("123456"))
            keyPass should be(KeyPass("12"))
            truststoreFile.value.name should be("ror-truststore.jks")
            truststorePassword should be(TruststorePassword("1234"))
            allowedProtocols should be(Set.empty)
            allowedCiphers should be(Set.empty)
            clientAuthenticationEnabled should be(false)
        }
        ssl.internodeSsl should be(None)
      }
      "server and client are configured using pem files" in {
        val ssl = forceLoadRorSslSettings("/boot_tests/es_api_ssl_settings_pem_files")
        inside(ssl.externalSsl) {
          case Some(ExternalSslSettings(FileBasedSettings(serverCertificateKeyFile, serverCertificateFile), Some(ClientCertificateSettings.FileBasedSettings(clientTrustedCertificateFile)), allowedProtocols, allowedCiphers, clientAuthenticationEnabled, FipsMode.NonFips)) =>
            serverCertificateKeyFile.value.name should be("server_certificate_key.pem")
            serverCertificateFile.value.name should be("server_certificate.pem")
            clientTrustedCertificateFile.value.name should be("client_certificate.pem")
            allowedProtocols should be(Set.empty)
            allowedCiphers should be(Set.empty)
            clientAuthenticationEnabled should be(false)
        }
      }
    }
    "be loaded from readonlyrest config file" when {
      "elasticsearch config file doesn't contain ROR ssl section" in {
        val ssl = forceLoadRorSslSettings("/boot_tests/es_api_ssl_settings_in_readonlyrest_config")
        inside(ssl.externalSsl) {
          case Some(ExternalSslSettings(KeystoreBasedSettings(keystoreFile, Some(keystorePassword), None, Some(keyPass)), Some(ClientCertificateSettings.TruststoreBasedSettings(truststoreFile, Some(truststorePassword))), allowedProtocols, allowedCiphers, clientAuthenticationEnabled, FipsMode.NonFips)) =>
            keystoreFile.value.name should be("ror-keystore.jks")
            keystorePassword should be(KeystorePassword("readonlyrest1"))
            keyPass should be(KeyPass("readonlyrest2"))
            truststoreFile.value.name should be("ror-truststore.jks")
            truststorePassword should be(TruststorePassword("readonlyrest3"))
            allowedProtocols should be(Set.empty)
            allowedCiphers should be(Set.empty)
            clientAuthenticationEnabled should be(false)
        }
        ssl.internodeSsl should be(None)
      }
    }
    "be disabled" when {
      "no ssl section is provided" in {
        val ssl = loadRorSslSettings("/boot_tests/no_es_api_ssl_settings")
        ssl should be (Right(None))
      }
      "it's disabled by proper settings" in {
        val ssl = loadRorSslSettings("/boot_tests/es_api_ssl_settings_disabled")
        ssl should be (Right(None))
      }
    }
    "not be able to load" when {
      "SSL settings are malformed" when {
        "keystore_file entry is missing" in {
          val esConfigFolderPath = "/boot_tests/es_api_ssl_settings_malformed"
          val expectedFilePath = getResourcePath(s"$esConfigFolderPath/elasticsearch.yml")
          loadRorSslSettings(esConfigFolderPath) shouldBe Left {
            MalformedSettings(expectedFilePath, s"Cannot load ROR SSL settings from file ${expectedFilePath.toString}. Cause: Missing required field")
          }
        }
      }
      "file content is not valid yaml" in {
        val error = loadRorSslSettings("/boot_tests/es_api_ssl_settings_file_invalid_yaml/")
        inside(error) {
          case Left(error) =>
            error.message should startWith("Cannot parse file")
        }
      }
      "SSL settings contain both pem and truststore based configuration" in {
        val configFolderPath = "/boot_tests/es_api_ssl_settings_both_pem_and_keystore_configured"
        val expectedFilePath = getResourcePath(s"$configFolderPath/elasticsearch.yml")

        loadRorSslSettings(configFolderPath) shouldBe Left {
          MalformedSettings(
            expectedFilePath,
            s"Cannot load ROR SSL settings from file ${expectedFilePath.toString}. " +
              s"Cause: Field sets [server_certificate_key_file, server_certificate_file] and [keystore_file, keystore_pass, key_pass, key_alias] could not be present in the same settings section")
        }
      }
    }
  }

  "A ReadonlyREST internode SSL settings" should {
    "be loaded from elasticsearch config file" in {
      val ssl = forceLoadRorSslSettings("/boot_tests/internode_ssl_settings_in_elasticsearch_config")
      inside(ssl.internodeSsl) {
        case Some(InternodeSslSettings(KeystoreBasedSettings(keystoreFile, Some(keystorePassword), None, Some(keyPass)), truststoreConfiguration, allowedProtocols, allowedCiphers, clientAuthenticationEnabled, certificateVerificationEnabled, hostnameVerificationEnabled, FipsMode.NonFips)) =>
          keystoreFile.value.name should be("ror-keystore.jks")
          keystorePassword should be(KeystorePassword("readonlyrest1"))
          keyPass should be(KeyPass("readonlyrest2"))
          truststoreConfiguration should be(None)
          allowedProtocols should be(Set.empty)
          allowedCiphers should be(Set.empty)
          clientAuthenticationEnabled should be(false)
          certificateVerificationEnabled should be(true)
          hostnameVerificationEnabled should be(true)
      }
      ssl.externalSsl should be(None)
    }
    "be loaded from elasticsearch config file when pem files are used" in {
      val ssl = forceLoadRorSslSettings("/boot_tests/internode_ssl_settings_pem_files")
      inside(ssl.internodeSsl) {
        case Some(InternodeSslSettings(FileBasedSettings(serverCertificateKeyFile, serverCertificateFile), Some(ClientCertificateSettings.FileBasedSettings(clientTrustedCertificateFile)), allowedProtocols, allowedCiphers, clientAuthenticationEnabled, certificateVerificationEnabled, hostnameVerificationEnabled, FipsMode.NonFips)) =>
          serverCertificateKeyFile.value.name should be("server_certificate_key.pem")
          serverCertificateFile.value.name should be("server_certificate.pem")
          clientTrustedCertificateFile.value.name should be("client_certificate.pem")
          allowedProtocols should be(Set.empty)
          allowedCiphers should be(Set.empty)
          clientAuthenticationEnabled should be(false)
          certificateVerificationEnabled should be(true)
          hostnameVerificationEnabled should be(false)
      }
    }
    "be loaded from readonlyrest settings file" when {
      "elasticsearch config file doesn't contain ROR ssl section" in {
        val ssl = forceLoadRorSslSettings("/boot_tests/internode_ssl_settings_in_readonlyrest_config")
        inside(ssl.internodeSsl) {
          case Some(InternodeSslSettings(KeystoreBasedSettings(keystoreFile, Some(keystorePassword), None, Some(keyPass)), Some(ClientCertificateSettings.TruststoreBasedSettings(truststoreFile, Some(truststorePassword))), allowedProtocols, allowedCiphers, clientAuthenticationEnabled, certificateVerificationEnabled, hostnameVerificationEnabled, FipsMode.NonFips)) =>
            keystoreFile.value.name should be("ror-keystore.jks")
            keystorePassword should be(KeystorePassword("readonlyrest1"))
            keyPass should be(KeyPass("readonlyrest2"))
            truststoreFile.value.name should be("ror-truststore.jks")
            truststorePassword should be(TruststorePassword("readonlyrest3"))
            allowedProtocols should be(Set.empty)
            allowedCiphers should be(Set.empty)
            clientAuthenticationEnabled should be(false)
            certificateVerificationEnabled should be(true)
            hostnameVerificationEnabled should be(true)
        }
        ssl.externalSsl should be(None)
      }
    }
    "be disabled" when {
      "no ssl section is provided" in {
        val ssl = loadRorSslSettings("/boot_tests/no_internode_ssl_settings")
        ssl should be (Right(None))
      }
      "it's disabled by proper settings" in {
        val ssl = loadRorSslSettings("/boot_tests/internode_ssl_settings_disabled")
        ssl should be (Right(None))
      }
    }
    "not be able to load" when {
      "SSL settings are malformed" when {
        "keystore_file entry is missing" in {
          val configFolderPath = "/boot_tests/internode_ssl_settings_malformed"
          val expectedFilePath = getResourcePath(s"$configFolderPath/elasticsearch.yml")
          loadRorSslSettings(configFolderPath) shouldBe Left {
            MalformedSettings(expectedFilePath, s"Cannot load ROR SSL settings from file ${expectedFilePath.toString}. Cause: Missing required field")
          }
        }
      }
    }
  }

  private def forceLoadRorSslSettings(settingsFolderPath: String) = {
    loadRorSslSettings(settingsFolderPath)
      .toOption.flatten.get
  }

  private def loadRorSslSettings(settingsFolderPath: String) = {
    val esConfigFile = File(getResourcePath(settingsFolderPath))
    val rorSettingsFile = RorSettingsFile(getResourcePath(s"$settingsFolderPath/readonlyrest.yml"))
    val esEnv = EsEnv(esConfigFile, esConfigFile, defaultEsVersionForTests, testEsNodeSettings)
    RorSslSettings
      .load(esEnv, rorSettingsFile)
      .runSyncUnsafe()
  }
}
