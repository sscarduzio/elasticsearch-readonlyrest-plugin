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
import tech.beshu.ror.configuration.SslConfiguration.{ClientCertificateConfiguration, _}
import tech.beshu.ror.configuration.{MalformedSettings, RorSsl}
import tech.beshu.ror.providers.{EnvVarsProvider, OsEnvVarsProvider}
import tech.beshu.ror.utils.TestsPropertiesProvider
import tech.beshu.ror.utils.TestsUtils.getResourcePath

class SslConfigurationTest
  extends AnyWordSpec with Inside {

  implicit private val envVarsProvider: EnvVarsProvider = OsEnvVarsProvider
  implicit private val propertiesProvider = TestsPropertiesProvider.default

  "A ReadonlyREST ES API SSL settings" should {
    "be loaded from elasticsearch config file" when {
      "all properties contain at least one non-digit" in {
        val ssl = RorSsl.load(getResourcePath("/boot_tests/es_api_ssl_settings_in_elasticsearch_config/")).runSyncUnsafe().right.get
        inside(ssl.externalSsl) {
          case Some(ExternalSslConfiguration(ServerCertificateConfiguration.KeystoreBasedConfiguration(keystoreFile, Some(keystorePassword), None, Some(keyPass)), Some(ClientCertificateConfiguration.TruststoreBasedConfiguration(truststoreFile, Some(truststorePassword))), allowedProtocols, allowedCiphers, clientAuthenticationEnabled)) =>
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
          case Some(ExternalSslConfiguration(ServerCertificateConfiguration.KeystoreBasedConfiguration(keystoreFile, Some(keystorePassword), None, Some(keyPass)), Some(ClientCertificateConfiguration.TruststoreBasedConfiguration(truststoreFile, Some(truststorePassword))), allowedProtocols, allowedCiphers, clientAuthenticationEnabled)) =>
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
      "server and client are configured using pem files" in {
        val ssl = RorSsl.load(getResourcePath("/boot_tests/es_api_ssl_settings_pem_files/")).runSyncUnsafe().right.get
        inside(ssl.externalSsl) {
          case Some(ExternalSslConfiguration(ServerCertificateConfiguration.FileBasedConfiguration(serverCertificateKeyFile, serverCertificateFile), Some(ClientCertificateConfiguration.FileBasedConfiguration(clientTrustedCertificateFile)), allowedProtocols, allowedCiphers, clientAuthenticationEnabled)) =>
            serverCertificateKeyFile.value.getName should be("server_certificate_key.pem")
            serverCertificateFile.value.getName should be("server_certificate.pem")
            clientTrustedCertificateFile.value.getName should be("client_certificate.pem")
            allowedProtocols should be(Set.empty)
            allowedCiphers should be(Set.empty)
            clientAuthenticationEnabled should be(false)
        }
      }
    }
    "be loaded from readonlyrest config file" when {
      "elasticsearch config file doesn't contain ROR ssl section" in {
        val ssl = RorSsl.load(getResourcePath("/boot_tests/es_api_ssl_settings_in_readonlyrest_config/")).runSyncUnsafe().right.get
        inside(ssl.externalSsl) {
          case Some(ExternalSslConfiguration(ServerCertificateConfiguration.KeystoreBasedConfiguration(keystoreFile, Some(keystorePassword), None, Some(keyPass)), Some(ClientCertificateConfiguration.TruststoreBasedConfiguration(truststoreFile, Some(truststorePassword))), allowedProtocols, allowedCiphers, clientAuthenticationEnabled)) =>
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
      "SSL settings contain both pem and truststore based configuration" in {
        RorSsl.load(getResourcePath("/boot_tests/es_api_ssl_settings_both_pem_and_keystore_configured/")).runSyncUnsafe() shouldBe Left {
          MalformedSettings("Invalid ROR SSL configuration")
        }
      }
    }
  }

  "A ReadonlyREST internode SSL settings" should {
    "be loaded from elasticsearch config file" in {
      val ssl = RorSsl.load(getResourcePath("/boot_tests/internode_ssl_settings_in_elasticsearch_config/")).runSyncUnsafe().right.get
      inside(ssl.interNodeSsl) {
        case Some(InternodeSslConfiguration(ServerCertificateConfiguration.KeystoreBasedConfiguration(keystoreFile, Some(keystorePassword), None, Some(keyPass)), truststoreConfiguration, allowedProtocols, allowedCiphers, certificateVerificationEnabled)) =>
          keystoreFile.value.getName should be("keystore.jks")
          keystorePassword should be(KeystorePassword("readonlyrest1"))
          keyPass should be(KeyPass("readonlyrest2"))
          truststoreConfiguration should be(None)
          allowedProtocols should be(Set.empty)
          allowedCiphers should be(Set.empty)
          certificateVerificationEnabled should be(true)
      }
      ssl.externalSsl should be(None)
    }
    "be loaded from elasticsearch config file when pem files are used" in {
      val ssl = RorSsl.load(getResourcePath("/boot_tests/internode_ssl_settings_pem_files/")).runSyncUnsafe().right.get
      inside(ssl.interNodeSsl) {
        case Some(InternodeSslConfiguration(ServerCertificateConfiguration.FileBasedConfiguration(serverCertificateKeyFile, serverCertificateFile), Some(ClientCertificateConfiguration.FileBasedConfiguration(clientTrustedCertificateFile)), allowedProtocols, allowedCiphers, certificateVerificationEnabled)) =>
          serverCertificateKeyFile.value.getName should be("server_certificate_key.pem")
          serverCertificateFile.value.getName should be("server_certificate.pem")
          clientTrustedCertificateFile.value.getName should be("client_certificate.pem")
          allowedProtocols should be(Set.empty)
          allowedCiphers should be(Set.empty)
          certificateVerificationEnabled should be(true)
      }
    }
    "be loaded from readonlyrest config file" when {
      "elasticsearch config file doesn't contain ROR ssl section" in {
        val ssl = RorSsl.load(getResourcePath("/boot_tests/internode_ssl_settings_in_readonlyrest_config/")).runSyncUnsafe().right.get
        inside(ssl.interNodeSsl) {
          case Some(InternodeSslConfiguration(ServerCertificateConfiguration.KeystoreBasedConfiguration(keystoreFile, Some(keystorePassword), None, Some(keyPass)), Some(ClientCertificateConfiguration.TruststoreBasedConfiguration(truststoreFile, Some(truststorePassword))), allowedProtocols, allowedCiphers, certificateVerificationEnabled)) =>
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
}
