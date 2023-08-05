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
import tech.beshu.ror.configuration.SslConfiguration.ServerCertificateConfiguration.{FileBasedConfiguration, KeystoreBasedConfiguration}
import tech.beshu.ror.configuration.SslConfiguration._
import tech.beshu.ror.configuration.{MalformedSettings, RorSsl, EnvironmentConfig}
import tech.beshu.ror.utils.TestsPropertiesProvider
import tech.beshu.ror.utils.TestsUtils.getResourcePath

class SslConfigurationTest
  extends AnyWordSpec with Inside {

  private implicit val environmentConfig: EnvironmentConfig = EnvironmentConfig.default.copy(
    propertiesProvider = TestsPropertiesProvider.default
  )

  "A ReadonlyREST ES API SSL settings" should {
    "be loaded from elasticsearch config file" when {
      "all properties contain at least one non-digit" in {
        val ssl = RorSsl.load(getResourcePath("/boot_tests/es_api_ssl_settings_in_elasticsearch_config/")).runSyncUnsafe().toOption.get
        inside(ssl.externalSsl) {
          case Some(ExternalSslConfiguration(KeystoreBasedConfiguration(keystoreFile, Some(keystorePassword), None, Some(keyPass)), Some(ClientCertificateConfiguration.TruststoreBasedConfiguration(truststoreFile, Some(truststorePassword))), allowedProtocols, allowedCiphers, clientAuthenticationEnabled)) =>
            keystoreFile.value.getName should be("ror-keystore.jks")
            keystorePassword should be(KeystorePassword("readonlyrest1"))
            keyPass should be(KeyPass("readonlyrest2"))
            truststoreFile.value.getName should be("ror-truststore.jks")
            truststorePassword should be(TruststorePassword("readonlyrest3"))
            allowedProtocols should be(Set.empty)
            allowedCiphers should be(Set.empty)
            clientAuthenticationEnabled should be(false)
        }
        ssl.interNodeSsl should be(None)
      }
      "some properties contains only digits" in {
        val ssl = RorSsl.load(getResourcePath("/boot_tests/es_api_ssl_settings_in_elasticsearch_config_only_digits/")).runSyncUnsafe().toOption.get
        inside(ssl.externalSsl) {
          case Some(ExternalSslConfiguration(KeystoreBasedConfiguration(keystoreFile, Some(keystorePassword), None, Some(keyPass)), Some(ClientCertificateConfiguration.TruststoreBasedConfiguration(truststoreFile, Some(truststorePassword))), allowedProtocols, allowedCiphers, clientAuthenticationEnabled)) =>
            keystoreFile.value.getName should be("ror-keystore.jks")
            keystorePassword should be(KeystorePassword("123456"))
            keyPass should be(KeyPass("12"))
            truststoreFile.value.getName should be("ror-truststore.jks")
            truststorePassword should be(TruststorePassword("1234"))
            allowedProtocols should be(Set.empty)
            allowedCiphers should be(Set.empty)
            clientAuthenticationEnabled should be(false)
        }
        ssl.interNodeSsl should be(None)
      }
      "server and client are configured using pem files" in {
        val ssl = RorSsl.load(getResourcePath("/boot_tests/es_api_ssl_settings_pem_files/")).runSyncUnsafe().toOption.get
        inside(ssl.externalSsl) {
          case Some(ExternalSslConfiguration(FileBasedConfiguration(serverCertificateKeyFile, serverCertificateFile), Some(ClientCertificateConfiguration.FileBasedConfiguration(clientTrustedCertificateFile)), allowedProtocols, allowedCiphers, clientAuthenticationEnabled)) =>
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
        val ssl = RorSsl.load(getResourcePath("/boot_tests/es_api_ssl_settings_in_readonlyrest_config/")).runSyncUnsafe().toOption.get
        inside(ssl.externalSsl) {
          case Some(ExternalSslConfiguration(KeystoreBasedConfiguration(keystoreFile, Some(keystorePassword), None, Some(keyPass)), Some(ClientCertificateConfiguration.TruststoreBasedConfiguration(truststoreFile, Some(truststorePassword))), allowedProtocols, allowedCiphers, clientAuthenticationEnabled)) =>
            keystoreFile.value.getName should be("ror-keystore.jks")
            keystorePassword should be(KeystorePassword("readonlyrest1"))
            keyPass should be(KeyPass("readonlyrest2"))
            truststoreFile.value.getName should be("ror-truststore.jks")
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
        val ssl = RorSsl.load(getResourcePath("/boot_tests/no_es_api_ssl_settings/")).runSyncUnsafe().toOption.get
        ssl.externalSsl should be(None)
        ssl.interNodeSsl should be(None)
      }
      "it's disabled by proper settings" in {
        val ssl = RorSsl.load(getResourcePath("/boot_tests/es_api_ssl_settings_disabled/")).runSyncUnsafe().toOption.get
        ssl.externalSsl should be(None)
        ssl.interNodeSsl should be(None)
      }
    }
    "not be able to load" when {
      "SSL settings are malformed" when {
        "keystore_file entry is missing" in {
          val configFolderPath = "/boot_tests/es_api_ssl_settings_malformed/"
          val expectedFilePath = getResourcePath(s"${configFolderPath}elasticsearch.yml").toString
          RorSsl.load(getResourcePath(configFolderPath)).runSyncUnsafe() shouldBe Left {
            MalformedSettings(s"Cannot load ROR SSL configuration from file $expectedFilePath. Cause: Missing required field")
          }
        }
      }
      "file content is not valid yaml" in {
        val error = RorSsl.load(getResourcePath("/boot_tests/es_api_ssl_settings_file_invalid_yaml/")).runSyncUnsafe()
        inside(error) {
          case Left(error) =>
            error.message should startWith("Cannot parse file")

        }
      }
      "SSL settings contain both pem and truststore based configuration" in {
        val configFolderPath = "/boot_tests/es_api_ssl_settings_both_pem_and_keystore_configured/"
        val expectedFilePath = getResourcePath(s"${configFolderPath}elasticsearch.yml").toString

        RorSsl.load(getResourcePath(configFolderPath)).runSyncUnsafe() shouldBe Left {
          MalformedSettings(
            s"Cannot load ROR SSL configuration from file $expectedFilePath. " +
              s"Cause: Field sets [server_certificate_key_file,server_certificate_file] and [keystore_file,keystore_pass,key_pass,key_alias] could not be present in the same configuration section")
        }
      }
    }
  }

  "A ReadonlyREST internode SSL settings" should {
    "be loaded from elasticsearch config file" in {
      val ssl = RorSsl.load(getResourcePath("/boot_tests/internode_ssl_settings_in_elasticsearch_config/")).runSyncUnsafe().toOption.get
      inside(ssl.interNodeSsl) {
        case Some(InternodeSslConfiguration(KeystoreBasedConfiguration(keystoreFile, Some(keystorePassword), None, Some(keyPass)), truststoreConfiguration, allowedProtocols, allowedCiphers, clientAuthenticationEnabled, certificateVerificationEnabled)) =>
          keystoreFile.value.getName should be("ror-keystore.jks")
          keystorePassword should be(KeystorePassword("readonlyrest1"))
          keyPass should be(KeyPass("readonlyrest2"))
          truststoreConfiguration should be(None)
          allowedProtocols should be(Set.empty)
          allowedCiphers should be(Set.empty)
          clientAuthenticationEnabled should be(false)
          certificateVerificationEnabled should be(true)
      }
      ssl.externalSsl should be(None)
    }
    "be loaded from elasticsearch config file when pem files are used" in {
      val ssl = RorSsl.load(getResourcePath("/boot_tests/internode_ssl_settings_pem_files/")).runSyncUnsafe().toOption.get
      inside(ssl.interNodeSsl) {
        case Some(InternodeSslConfiguration(FileBasedConfiguration(serverCertificateKeyFile, serverCertificateFile), Some(ClientCertificateConfiguration.FileBasedConfiguration(clientTrustedCertificateFile)), allowedProtocols, allowedCiphers, clientAuthenticationEnabled, certificateVerificationEnabled)) =>
          serverCertificateKeyFile.value.getName should be("server_certificate_key.pem")
          serverCertificateFile.value.getName should be("server_certificate.pem")
          clientTrustedCertificateFile.value.getName should be("client_certificate.pem")
          allowedProtocols should be(Set.empty)
          allowedCiphers should be(Set.empty)
          clientAuthenticationEnabled should be(false)
          certificateVerificationEnabled should be(true)
      }
    }
    "be loaded from readonlyrest config file" when {
      "elasticsearch config file doesn't contain ROR ssl section" in {
        val ssl = RorSsl.load(getResourcePath("/boot_tests/internode_ssl_settings_in_readonlyrest_config/")).runSyncUnsafe().toOption.get
        inside(ssl.interNodeSsl) {
          case Some(InternodeSslConfiguration(KeystoreBasedConfiguration(keystoreFile, Some(keystorePassword), None, Some(keyPass)), Some(ClientCertificateConfiguration.TruststoreBasedConfiguration(truststoreFile, Some(truststorePassword))), allowedProtocols, allowedCiphers, clientAuthenticationEnabled, certificateVerificationEnabled)) =>
            keystoreFile.value.getName should be("ror-keystore.jks")
            keystorePassword should be(KeystorePassword("readonlyrest1"))
            keyPass should be(KeyPass("readonlyrest2"))
            truststoreFile.value.getName should be("ror-truststore.jks")
            truststorePassword should be(TruststorePassword("readonlyrest3"))
            allowedProtocols should be(Set.empty)
            allowedCiphers should be(Set.empty)
            clientAuthenticationEnabled should be(false)
            certificateVerificationEnabled should be(true)
        }
        ssl.externalSsl should be(None)
      }
    }
    "be disabled" when {
      "no ssl section is provided" in {
        val ssl = RorSsl.load(getResourcePath("/boot_tests/no_internode_ssl_settings/")).runSyncUnsafe().toOption.get
        ssl.externalSsl should be(None)
        ssl.interNodeSsl should be(None)
      }
      "it's disabled by proper settings" in {
        val ssl = RorSsl.load(getResourcePath("/boot_tests/internode_ssl_settings_disabled/")).runSyncUnsafe().toOption.get
        ssl.externalSsl should be(None)
        ssl.interNodeSsl should be(None)
      }
    }
    "not be able to load" when {
      "SSL settings are malformed" when {
        "keystore_file entry is missing" in {
          val configFolderPath = "/boot_tests/internode_ssl_settings_malformed/"
          val expectedFilePath = getResourcePath(s"${configFolderPath}elasticsearch.yml").toString
          RorSsl.load(getResourcePath(configFolderPath)).runSyncUnsafe() shouldBe Left {
            MalformedSettings(s"Cannot load ROR SSL configuration from file $expectedFilePath. Cause: Missing required field")
          }
        }
      }
    }
  }
}
