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

import monix.execution.Scheduler.Implicits.global
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.SystemContext
import tech.beshu.ror.accesscontrol.domain.RorSettingsFile
import tech.beshu.ror.es.{EsEnv, EsNodeSettings}
import tech.beshu.ror.settings.es.RorSslSettings
import tech.beshu.ror.settings.es.SslSettings.*
import tech.beshu.ror.settings.es.SslSettings.ServerCertificateSettings.{FileBasedSettings, KeystoreBasedSettings}
import tech.beshu.ror.settings.es.ElasticsearchConfigLoader.LoadingError
import tech.beshu.ror.settings.es.ElasticsearchConfigLoader.LoadingError.MalformedSettings
import tech.beshu.ror.utils.{TestsEnvVarsProvider, TestsPropertiesProvider}
import tech.beshu.ror.utils.TestsUtils.{defaultEsVersionForTests, withEsEnv, withTempConfigDir}

class RorSslSettingsTest
  extends AnyWordSpec with Inside {

  "A ReadonlyREST ES API SSL settings" should {
    "be loaded from elasticsearch config file" when {
      "all properties contain at least one non-digit" in {
        val ssl = forceLoad(
          """
            |node.name: n1_it
            |cluster.initial_master_nodes: n1_it
            |xpack.security.enabled: false
            |
            |readonlyrest:
            |  ssl:
            |    enable: true
            |    keystore_file: "ror-keystore.jks"
            |    keystore_pass: readonlyrest1
            |    key_pass: readonlyrest2
            |    truststore_file: "ror-truststore.jks"
            |    truststore_pass: readonlyrest3
            |""".stripMargin
        )
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
        val ssl = forceLoad(
          """
            |node.name: n1_it
            |cluster.initial_master_nodes: n1_it
            |xpack.security.enabled: false
            |
            |readonlyrest:
            |  ssl:
            |    enable: true
            |    keystore_file: "ror-keystore.jks"
            |    keystore_pass: "123456"
            |    key_pass: 12
            |    truststore_file: "ror-truststore.jks"
            |    truststore_pass: "1234"
            |""".stripMargin
        )
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
        val ssl = forceLoad(
          """
            |node.name: n1_it
            |cluster.initial_master_nodes: n1_it
            |xpack.security.enabled: false
            |
            |readonlyrest.ssl.enable: true
            |readonlyrest.ssl.server_certificate_file: "server_certificate.pem"
            |readonlyrest.ssl.server_certificate_key_file: "server_certificate_key.pem"
            |readonlyrest.ssl.client_trusted_certificate_file: "client_certificate.pem"
            |""".stripMargin
        )
        inside(ssl.externalSsl) {
          case Some(ExternalSslSettings(FileBasedSettings(serverCertificateKeyFile, serverCertificateFile), Some(ClientCertificateSettings.FileBasedSettings(clientTrustedCertificateFile)), allowedProtocols, allowedCiphers, clientAuthenticationEnabled, FipsMode.NonFips)) =>
            serverCertificateKeyFile.value.name should be("server_certificate_key.pem")
            serverCertificateFile.value.name should be("server_certificate.pem")
            clientTrustedCertificateFile.value.name should be("client_certificate.pem")
            allowedProtocols should be(Set.empty)
            allowedCiphers should be(Set.empty)
            clientAuthenticationEnabled should be(false)
        }
        ssl.internodeSsl should be(None)
      }
    }
    "be loaded from readonlyrest config file" when {
      "elasticsearch config file doesn't contain ROR SSL section" in {
        val ssl = forceLoad(
          esConfigYaml = """
            |node.name: n1_it
            |cluster.initial_master_nodes: n1_it
            |xpack.security.enabled: false
            |""".stripMargin,
          rorSettingsYaml = """
            |readonlyrest:
            |  ssl:
            |    enable: true
            |    keystore_file: "ror-keystore.jks"
            |    keystore_pass: readonlyrest1
            |    key_pass: readonlyrest2
            |    truststore_file: "ror-truststore.jks"
            |    truststore_pass: readonlyrest3
            |""".stripMargin
        )
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
        val ssl = load(
          """
            |node.name: n1_it
            |cluster.initial_master_nodes: n1_it
            |xpack.security.enabled: false
            |""".stripMargin
        )
        ssl should be(Right(None))
      }
      "it's disabled by proper settings" in {
        val ssl = load(
          """
            |node.name: n1_it
            |cluster.initial_master_nodes: n1_it
            |xpack.security.enabled: false
            |
            |readonlyrest:
            |  ssl:
            |    enable: false
            |    keystore_file: "ror-keystore.jks"
            |    keystore_pass: readonlyrest1
            |    key_pass: readonlyrest2
            |""".stripMargin
        )
        ssl should be(Right(None))
      }
    }
    "load FIPS mode SSL_ONLY" in {
      val ssl = forceLoad(
        """
          |node.name: n1_it
          |cluster.initial_master_nodes: n1_it
          |xpack.security.enabled: false
          |
          |readonlyrest:
          |  fips_mode: SSL_ONLY
          |  ssl:
          |    enable: true
          |    keystore_file: "ror-keystore.jks"
          |    keystore_pass: readonlyrest1
          |    key_pass: readonlyrest2
          |""".stripMargin
      )
      inside(ssl.externalSsl) {
        case Some(ExternalSslSettings(KeystoreBasedSettings(keystoreFile, Some(keystorePassword), None, Some(keyPass)), None, _, _, _, FipsMode.SslOnly)) =>
          keystoreFile.value.name should be("ror-keystore.jks")
          keystorePassword should be(KeystorePassword("readonlyrest1"))
          keyPass should be(KeyPass("readonlyrest2"))
      }
      ssl.internodeSsl should be(None)
    }
    "be loaded from OS environment variables" when {
      "external SSL is configured via env vars" in {
        val ssl = forceLoad(
          """
            |node.name: n1_it
            |cluster.initial_master_nodes: n1_it
            |xpack.security.enabled: false
            |""".stripMargin,
          envVars = Map(
            "ES_SETTING_READONLYREST_SSL_ENABLE"        -> "true",
            "ES_SETTING_READONLYREST_SSL_KEYSTORE__FILE" -> "ror-keystore.jks",
            "ES_SETTING_READONLYREST_SSL_KEYSTORE__PASS" -> "readonlyrest1",
            "ES_SETTING_READONLYREST_SSL_KEY__PASS"      -> "readonlyrest2"
          )
        )
        inside(ssl.externalSsl) {
          case Some(ExternalSslSettings(KeystoreBasedSettings(keystoreFile, Some(keystorePassword), None, Some(keyPass)), None, allowedProtocols, allowedCiphers, clientAuthenticationEnabled, FipsMode.NonFips)) =>
            keystoreFile.value.name should be("ror-keystore.jks")
            keystorePassword should be(KeystorePassword("readonlyrest1"))
            keyPass should be(KeyPass("readonlyrest2"))
            allowedProtocols should be(Set.empty)
            allowedCiphers should be(Set.empty)
            clientAuthenticationEnabled should be(false)
        }
        ssl.internodeSsl should be(None)
      }
      "internode SSL is configured via env vars" in {
        val ssl = forceLoad(
          """
            |node.name: n1_it
            |cluster.initial_master_nodes: n1_it
            |xpack.security.enabled: false
            |""".stripMargin,
          envVars = Map(
            "ES_SETTING_READONLYREST_SSL__INTERNODE_ENABLE"        -> "true",
            "ES_SETTING_READONLYREST_SSL__INTERNODE_KEYSTORE__FILE" -> "ror-keystore.jks",
            "ES_SETTING_READONLYREST_SSL__INTERNODE_KEYSTORE__PASS" -> "readonlyrest1",
            "ES_SETTING_READONLYREST_SSL__INTERNODE_KEY__PASS"      -> "readonlyrest2"
          )
        )
        inside(ssl.internodeSsl) {
          case Some(InternodeSslSettings(KeystoreBasedSettings(keystoreFile, Some(keystorePassword), None, Some(keyPass)), None, allowedProtocols, allowedCiphers, clientAuthenticationEnabled, certificateVerificationEnabled, hostnameVerificationEnabled, FipsMode.NonFips)) =>
            keystoreFile.value.name should be("ror-keystore.jks")
            keystorePassword should be(KeystorePassword("readonlyrest1"))
            keyPass should be(KeyPass("readonlyrest2"))
            allowedProtocols should be(Set.empty)
            allowedCiphers should be(Set.empty)
            clientAuthenticationEnabled should be(false)
            certificateVerificationEnabled should be(false)
            hostnameVerificationEnabled should be(false)
        }
        ssl.externalSsl should be(None)
      }
      "fips_mode is configured via env var" in {
        val ssl = forceLoad(
          """
            |node.name: n1_it
            |cluster.initial_master_nodes: n1_it
            |xpack.security.enabled: false
            |""".stripMargin,
          envVars = Map(
            "ES_SETTING_READONLYREST_FIPS__MODE"         -> "SSL_ONLY",
            "ES_SETTING_READONLYREST_SSL_ENABLE"        -> "true",
            "ES_SETTING_READONLYREST_SSL_KEYSTORE__FILE" -> "ror-keystore.jks",
            "ES_SETTING_READONLYREST_SSL_KEYSTORE__PASS" -> "readonlyrest1",
            "ES_SETTING_READONLYREST_SSL_KEY__PASS"      -> "readonlyrest2"
          )
        )
        inside(ssl.externalSsl) {
          case Some(ExternalSslSettings(KeystoreBasedSettings(_, _, _, _), None, _, _, _, FipsMode.SslOnly)) =>
        }
      }
    }
    "be loaded from JVM properties" when {
      "external SSL is configured via properties" in {
        val ssl = forceLoad(
          """
            |node.name: n1_it
            |cluster.initial_master_nodes: n1_it
            |xpack.security.enabled: false
            |""".stripMargin,
          properties = Map(
            "readonlyrest.ssl.enable"        -> "true",
            "readonlyrest.ssl.keystore_file" -> "ror-keystore.jks",
            "readonlyrest.ssl.keystore_pass" -> "readonlyrest1",
            "readonlyrest.ssl.key_pass"      -> "readonlyrest2"
          )
        )
        inside(ssl.externalSsl) {
          case Some(ExternalSslSettings(KeystoreBasedSettings(keystoreFile, Some(keystorePassword), None, Some(keyPass)), None, allowedProtocols, allowedCiphers, clientAuthenticationEnabled, FipsMode.NonFips)) =>
            keystoreFile.value.name should be("ror-keystore.jks")
            keystorePassword should be(KeystorePassword("readonlyrest1"))
            keyPass should be(KeyPass("readonlyrest2"))
            allowedProtocols should be(Set.empty)
            allowedCiphers should be(Set.empty)
            clientAuthenticationEnabled should be(false)
        }
        ssl.internodeSsl should be(None)
      }
      "internode SSL is configured via properties" in {
        val ssl = forceLoad(
          """
            |node.name: n1_it
            |cluster.initial_master_nodes: n1_it
            |xpack.security.enabled: false
            |""".stripMargin,
          properties = Map(
            "readonlyrest.ssl_internode.enable"        -> "true",
            "readonlyrest.ssl_internode.keystore_file" -> "ror-keystore.jks",
            "readonlyrest.ssl_internode.keystore_pass" -> "readonlyrest1",
            "readonlyrest.ssl_internode.key_pass"      -> "readonlyrest2"
          )
        )
        inside(ssl.internodeSsl) {
          case Some(InternodeSslSettings(KeystoreBasedSettings(keystoreFile, Some(keystorePassword), None, Some(keyPass)), None, allowedProtocols, allowedCiphers, clientAuthenticationEnabled, certificateVerificationEnabled, hostnameVerificationEnabled, FipsMode.NonFips)) =>
            keystoreFile.value.name should be("ror-keystore.jks")
            keystorePassword should be(KeystorePassword("readonlyrest1"))
            keyPass should be(KeyPass("readonlyrest2"))
            allowedProtocols should be(Set.empty)
            allowedCiphers should be(Set.empty)
            clientAuthenticationEnabled should be(false)
            certificateVerificationEnabled should be(false)
            hostnameVerificationEnabled should be(false)
        }
        ssl.externalSsl should be(None)
      }
      "fips_mode is configured via property" in {
        val ssl = forceLoad(
          """
            |node.name: n1_it
            |cluster.initial_master_nodes: n1_it
            |xpack.security.enabled: false
            |""".stripMargin,
          properties = Map(
            "readonlyrest.fips_mode"         -> "SSL_ONLY",
            "readonlyrest.ssl.enable"        -> "true",
            "readonlyrest.ssl.keystore_file" -> "ror-keystore.jks",
            "readonlyrest.ssl.keystore_pass" -> "readonlyrest1",
            "readonlyrest.ssl.key_pass"      -> "readonlyrest2"
          )
        )
        inside(ssl.externalSsl) {
          case Some(ExternalSslSettings(KeystoreBasedSettings(_, _, _, _), None, _, _, _, FipsMode.SslOnly)) =>
        }
      }
    }
    "not be able to load" when {
      "SSL settings are malformed" when {
        "keystore_file entry is missing" in {
          inside(load(
            """
              |node.name: n1_it
              |cluster.initial_master_nodes: n1_it
              |xpack.security.enabled: false
              |
              |readonlyrest:
              |  ssl:
              |    keystore_pass: readonlyrest1
              |    key_pass: readonlyrest2
              |""".stripMargin
          )) {
            case Left(MalformedSettings(_, message)) =>
              message should include("'keystore_file' is required when keystore based SSL settings are used")
          }
        }
      }
      "file content is not valid yaml" in {
        withTempConfigDir { configDir =>
          (configDir / "elasticsearch.yml").writeText(
            """
              |node.name: n1_it
              |cluster.initial_master_nodes: n1_it
              |xpack.security.enabled: false
              |
              |readonlyrest:
              |  ssl:
              |    keystore_pass: "readonlyrest1
              |    key_pass: "readonlyrest2"
              |""".stripMargin
          )
          (configDir / "readonlyrest.yml").writeText("readonlyrest:\n")
          val esNodeSettings = EsNodeSettings(clusterName = "testEsCluster", nodeName = "testEsNode", xpackSecurityEnabled = false)
          val esEnv = EsEnv(configDir, configDir, defaultEsVersionForTests, esNodeSettings)
          implicit val systemContext: SystemContext = SystemContext.default
          val error = RorSslSettings.load(esEnv, RorSettingsFile(configDir / "readonlyrest.yml")).runSyncUnsafe()
          inside(error) {
            case Left(error: LoadingError.MalformedSettings) =>
              error.message should startWith("Cannot parse file")
          }
        }
      }
      "SSL settings contain both pem and truststore based configuration" in {
        inside(load(
          """
            |node.name: n1_it
            |cluster.initial_master_nodes: n1_it
            |xpack.security.enabled: false
            |
            |readonlyrest:
            |  ssl:
            |    enable: true
            |    keystore_file: "keystore.jks"
            |    keystore_pass: readonlyrest1
            |    key_pass: readonlyrest2
            |    server_certificate_file: "server_certificate.pem"
            |    server_certificate_key_file: "server_certificate_key.pem"
            |    truststore_file: "truststore.jks"
            |    truststore_pass: readonlyrest3
            |""".stripMargin
        )) {
          case Left(MalformedSettings(_, message)) =>
            message should include(
              "Field sets [server_certificate_key_file, server_certificate_file] and [keystore_file, keystore_pass, key_alias, key_pass] could not be present in the same settings section"
            )
        }
      }
    }
  }

  "A ReadonlyREST internode SSL settings" should {
    "be loaded from elasticsearch config file" in {
      val ssl = forceLoad(
        """
          |node.name: n1_it
          |cluster.initial_master_nodes: n1_it
          |xpack.security.enabled: false
          |
          |readonlyrest:
          |  ssl_internode:
          |    enable: true
          |    keystore_file: "ror-keystore.jks"
          |    keystore_pass: readonlyrest1
          |    key_pass: readonlyrest2
          |    verification: true
          |    hostname_verification: true
          |""".stripMargin
      )
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
      val ssl = forceLoad(
        """
          |node.name: n1_it
          |cluster.initial_master_nodes: n1_it
          |xpack.security.enabled: false
          |
          |readonlyrest:
          |  ssl_internode:
          |    enable: true
          |    server_certificate_file: "server_certificate.pem"
          |    server_certificate_key_file: "server_certificate_key.pem"
          |    client_trusted_certificate_file: "client_certificate.pem"
          |    verification: true
          |    hostname_verification: false
          |""".stripMargin
      )
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
      ssl.externalSsl should be(None)
    }
    "be loaded from readonlyrest settings file" when {
      "elasticsearch config file doesn't contain ROR ssl section" in {
        val ssl = forceLoad(
          esConfigYaml = """
            |node.name: n1_it
            |cluster.initial_master_nodes: n1_it
            |xpack.security.enabled: false
            |""".stripMargin,
          rorSettingsYaml = """
            |readonlyrest:
            |  ssl_internode:
            |    enable: true
            |    keystore_file: "ror-keystore.jks"
            |    keystore_pass: readonlyrest1
            |    key_pass: readonlyrest2
            |    truststore_file: "ror-truststore.jks"
            |    truststore_pass: readonlyrest3
            |    certificate_verification: true
            |    hostname_verification: true
            |""".stripMargin
        )
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
        load(
          """
            |node.name: n1_it
            |cluster.initial_master_nodes: n1_it
            |xpack.security.enabled: false
            |""".stripMargin
        ) should be(Right(None))
      }
      "it's disabled by proper settings" in {
        load(
          """
            |node.name: n1_it
            |cluster.initial_master_nodes: n1_it
            |xpack.security.enabled: false
            |
            |readonlyrest:
            |  ssl_internode:
            |    enable: false
            |    keystore_file: "ror-keystore.jks"
            |    keystore_pass: readonlyrest1
            |    key_pass: readonlyrest2
            |""".stripMargin
        ) should be(Right(None))
      }
    }
    "load both external and internode SSL settings" in {
      val ssl = forceLoad(
        """
          |node.name: n1_it
          |cluster.initial_master_nodes: n1_it
          |xpack.security.enabled: false
          |
          |readonlyrest:
          |  ssl:
          |    enable: true
          |    keystore_file: "ror-keystore.jks"
          |    keystore_pass: readonlyrest1
          |    key_pass: readonlyrest2
          |  ssl_internode:
          |    enable: true
          |    keystore_file: "ror-internode-keystore.jks"
          |    keystore_pass: readonlyrest3
          |    key_pass: readonlyrest4
          |    certificate_verification: true
          |    hostname_verification: true
          |""".stripMargin
      )
      inside(ssl) {
        case RorSslSettings.ExternalAndInternodeSslSettings(external, internode) =>
          inside(external.serverCertificateSettings) {
            case KeystoreBasedSettings(keystoreFile, Some(keystorePassword), None, Some(keyPass)) =>
              keystoreFile.value.name should be("ror-keystore.jks")
              keystorePassword should be(KeystorePassword("readonlyrest1"))
              keyPass should be(KeyPass("readonlyrest2"))
          }
          external.fipsMode should be(FipsMode.NonFips)
          inside(internode.serverCertificateSettings) {
            case KeystoreBasedSettings(keystoreFile, Some(keystorePassword), None, Some(keyPass)) =>
              keystoreFile.value.name should be("ror-internode-keystore.jks")
              keystorePassword should be(KeystorePassword("readonlyrest3"))
              keyPass should be(KeyPass("readonlyrest4"))
          }
          internode.certificateVerificationEnabled should be(true)
          internode.hostnameVerificationEnabled should be(true)
      }
    }
    "not be able to load" when {
      "SSL settings are malformed" when {
        "keystore_file entry is missing" in {
          inside(load(
            """
              |node.name: n1_it
              |cluster.initial_master_nodes: n1_it
              |xpack.security.enabled: false
              |
              |readonlyrest:
              |  ssl_internode:
              |    keystore_pass: readonlyrest1
              |    key_pass: readonlyrest2
              |""".stripMargin
          )) {
            case Left(MalformedSettings(_, message)) =>
              message should include("'keystore_file' is required when keystore based SSL settings are used")
          }
        }
      }
      "XPack Security is enabled and SSL is declared in elasticsearch config" in {
        inside(load(
          """
            |node.name: n1_it
            |cluster.initial_master_nodes: n1_it
            |xpack.security.enabled: true
            |
            |readonlyrest:
            |  force_load_from_file: true
            |  ssl_internode:
            |    enable: true
            |    keystore_file: "ror-keystore.jks"
            |    keystore_pass: readonlyrest1
            |    key_pass: readonlyrest2
            |""".stripMargin
        )) {
          case Left(MalformedSettings(_, message)) =>
            message should include("Cannot use ROR SSL when XPack Security is enabled")
        }
      }
      "XPack Security is enabled and SSL is declared in readonlyrest settings file" in {
        inside(load(
          esConfigYaml = """
            |node.name: n1_it
            |cluster.initial_master_nodes: n1_it
            |xpack.security.enabled: true
            |
            |readonlyrest:
            |  force_load_from_file: true
            |""".stripMargin,
          rorSettingsYaml = """
            |readonlyrest:
            |  ssl_internode:
            |    enable: true
            |    keystore_file: "ror-keystore.jks"
            |    keystore_pass: readonlyrest1
            |    key_pass: readonlyrest2
            |    truststore_file: "ror-truststore.jks"
            |    truststore_pass: readonlyrest3
            |    certificate_verification: true
            |""".stripMargin
        )) {
          case Left(MalformedSettings(_, message)) =>
            message should include("Cannot use ROR SSL when XPack Security is enabled")
        }
      }
    }
  }

  private def forceLoad(esConfigYaml: String,
                        rorSettingsYaml: String = basicRorSettings,
                        properties: Map[String, String] = Map.empty,
                        envVars: Map[String, String] = Map.empty) = {
    load(esConfigYaml, rorSettingsYaml, properties, envVars) match {
      case Right(Some(sslSettings)) => sslSettings
      case Right(None)              => throw new IllegalStateException("No SSL settings to load")
      case Left(error)              => throw new IllegalStateException(s"Cannot load SSL settings: $error")
    }
  }

  private def load(esConfigYaml: String,
                   rorSettingsYaml: String = basicRorSettings,
                   properties: Map[String, String] = Map.empty,
                   envVars: Map[String, String] = Map.empty) = {
    implicit val systemContext: SystemContext = new SystemContext(
      propertiesProvider = TestsPropertiesProvider.usingMap(properties),
      envVarsProvider    = TestsEnvVarsProvider.usingMap(envVars)
    )
    withEsEnv(esConfigYaml, Map("readonlyrest.yml" -> rorSettingsYaml)) { (esEnv, configDir) =>
      RorSslSettings.load(esEnv, RorSettingsFile(configDir / "readonlyrest.yml")).runSyncUnsafe()
    }
  }

  private lazy val basicRorSettings =
    s"""
       |readonlyrest:
       |  access_control_rules:
       |    - name: "ADMIN"
       |      auth_key: admin:admin
       |""".stripMargin
}
