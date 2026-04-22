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
package tech.beshu.ror.settings.es

import better.files.*
import cats.data.{EitherT, NonEmptyList}
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import tech.beshu.ror.SystemContext
import tech.beshu.ror.accesscontrol.domain.{EsConfigFile, RorSettingsFile}
import tech.beshu.ror.es.EsEnv
import tech.beshu.ror.implicits.*
import tech.beshu.ror.providers.PropertiesProvider
import tech.beshu.ror.settings.es.SslSettings.*
import tech.beshu.ror.settings.es.ElasticsearchConfigLoader.LoadingError
import tech.beshu.ror.utils.{FromString, RequestIdAwareLogging, SSLCertHelper}
import tech.beshu.ror.utils.yaml.YamlLeafOrPropertyDecoder

sealed trait RorSslSettings
object RorSslSettings extends ElasticsearchConfigLoaderSupport with RequestIdAwareLogging {

  final case class OnlyExternalSslSettings(ssl: ExternalSslSettings) extends RorSslSettings
  final case class OnlyInternodeSslSettings(ssl: InternodeSslSettings) extends RorSslSettings
  final case class ExternalAndInternodeSslSettings(external: ExternalSslSettings,
                                                   internode: InternodeSslSettings)
    extends RorSslSettings

  implicit class ExtractSsl(val rorSsl: RorSslSettings) extends AnyVal {
    def externalSsl: Option[ExternalSslSettings] = rorSsl match {
      case OnlyExternalSslSettings(ssl) => Some(ssl)
      case OnlyInternodeSslSettings(_) => None
      case ExternalAndInternodeSslSettings(ssl, _) => Some(ssl)
    }

    def internodeSsl: Option[InternodeSslSettings] = rorSsl match {
      case OnlyExternalSslSettings(_) => None
      case OnlyInternodeSslSettings(ssl) => Some(ssl)
      case ExternalAndInternodeSslSettings(_, ssl) => Some(ssl)
    }
  }

  implicit class IsSslFipsCompliant(val fipsMode: FipsMode) extends AnyVal {
    def isSslFipsCompliant: Boolean = fipsMode match {
      case FipsMode.NonFips => false
      case FipsMode.SslOnly => true
    }
  }

  def load(esEnv: EsEnv,
           rorSettingsFile: RorSettingsFile)
          (implicit systemContext: SystemContext): Task[Either[LoadingError, Option[RorSslSettings]]] = {
    loadRorSslSetting(
      esEnv.elasticsearchConfig,
      rorSettingsFile,
      XpackSecuritySettings(esEnv.esNodeSettings.xpackSecurityEnabled)
    ).value
  }

  private def loadRorSslSetting(esConfigFile: EsConfigFile,
                                rorSettingsFile: RorSettingsFile,
                                xpackSecuritySettings: XpackSecuritySettings)
                               (implicit systemContext: SystemContext): EitherT[Task, LoadingError, Option[RorSslSettings]] = {
    implicit val rorSslSettingsDecoder: YamlLeafOrPropertyDecoder[Option[RorSslSettings]] =
      SslDecoders.rorSslDecoder(esConfigFile.file.parent)
    loadSslSettingsFrom(esConfigFile.file)
      .flatMap {
        case None =>
          fallbackToRorSettingsFile(rorSettingsFile)
        case Some(ssl) =>
          EitherT.rightT(Some(ssl))
      }
      .subflatMap {
        case Some(ssl) if xpackSecuritySettings.enabled =>
          Left(LoadingError.MalformedSettings(esConfigFile.file, "Cannot use ROR SSL when XPack Security is enabled"): LoadingError)
        case rorSsl@(Some(_) | None) =>
          Right(rorSsl)
      }
  }

  private def fallbackToRorSettingsFile(rorSettingsFile: RorSettingsFile)
                                       (implicit decoder: YamlLeafOrPropertyDecoder[Option[RorSslSettings]],
                                        systemContext: SystemContext): EitherT[Task, LoadingError, Option[RorSslSettings]] = {
    val settingsFile = rorSettingsFile.file
    if (settingsFile.exists) {
      for {
        settings <- loadSslSettingsFrom(settingsFile)
        _ <- lift(settings match {
          case None => noRequestIdLogger.warn(s"Defining SSL settings in ReadonlyREST file is deprecated and will be removed in the future. Move your ReadonlyREST SSL settings to Elasticsearch config file. See https://docs.readonlyrest.com/elasticsearch#encryption for details")
          case Some(_) =>
        })
      } yield settings
    } else {
      EitherT.rightT(None)
    }
  }

  private def loadSslSettingsFrom(settingsFile: File)
                                 (implicit decoder: YamlLeafOrPropertyDecoder[Option[RorSslSettings]],
                                  systemContext: SystemContext) = {
    for {
      _ <- lift(noRequestIdLogger.info(s"Trying to load ROR SSL settings from '${settingsFile.show}' file ..."))
      settings <- EitherT(loadSetting[Option[RorSslSettings]](settingsFile, "ROR SSL settings"))
      _ <- lift(noRequestIdLogger.info(settings match {
        case Some(_) => s"ROR SSL settings loaded from '${settingsFile.show}' file."
        case None => s"No ROR SSL settings found in '${settingsFile.show}' file."
      }))
    } yield settings
  }

  private final case class XpackSecuritySettings(enabled: Boolean)

  private def lift[T](value: => T): EitherT[Task, LoadingError, T] = EitherT.rightT(value)
}

sealed trait SslSettings {
  def serverCertificateSettings: SslSettings.ServerCertificateSettings

  def clientCertificateSettings: Option[SslSettings.ClientCertificateSettings]

  def allowedProtocols: Set[SslSettings.Protocol]

  def allowedCiphers: Set[SslSettings.Cipher]

  def clientAuthenticationEnabled: Boolean

  def certificateVerificationEnabled: Boolean

  def fipsMode: FipsMode
}

object SslSettings {

  final case class KeystorePassword(value: String)
  final case class KeystoreFile(value: File)
  final case class TruststorePassword(value: String)
  final case class TruststoreFile(value: File)
  final case class ServerCertificateKeyFile(value: File)
  final case class ServerCertificateFile(value: File)
  final case class ClientTrustedCertificateFile(value: File)
  final case class KeyPass(value: String)
  final case class KeyAlias(value: String)
  final case class Cipher(value: String)
  final case class Protocol(value: String)

  sealed trait ServerCertificateSettings
  object ServerCertificateSettings {
    final case class KeystoreBasedSettings(keystoreFile: KeystoreFile,
                                           keystorePassword: Option[KeystorePassword],
                                           keyAlias: Option[KeyAlias],
                                           keyPass: Option[KeyPass])
      extends ServerCertificateSettings
    final case class FileBasedSettings(serverCertificateKeyFile: ServerCertificateKeyFile,
                                       serverCertificateFile: ServerCertificateFile)
      extends ServerCertificateSettings
  }

  sealed trait ClientCertificateSettings
  object ClientCertificateSettings {
    final case class TruststoreBasedSettings(truststoreFile: TruststoreFile,
                                             truststorePassword: Option[TruststorePassword])
      extends ClientCertificateSettings
    final case class FileBasedSettings(clientTrustedCertificateFile: ClientTrustedCertificateFile)
      extends ClientCertificateSettings
  }

  final case class ExternalSslSettings(serverCertificateSettings: ServerCertificateSettings,
                                       clientCertificateSettings: Option[ClientCertificateSettings],
                                       allowedProtocols: Set[SslSettings.Protocol],
                                       allowedCiphers: Set[SslSettings.Cipher],
                                       clientAuthenticationEnabled: Boolean,
                                       fipsMode: FipsMode)
    extends SslSettings {

    override val certificateVerificationEnabled: Boolean = false
  }

  final case class InternodeSslSettings(serverCertificateSettings: ServerCertificateSettings,
                                        clientCertificateSettings: Option[ClientCertificateSettings],
                                        allowedProtocols: Set[SslSettings.Protocol],
                                        allowedCiphers: Set[SslSettings.Cipher],
                                        clientAuthenticationEnabled: Boolean,
                                        certificateVerificationEnabled: Boolean,
                                        hostnameVerificationEnabled: Boolean,
                                        fipsMode: FipsMode)
    extends SslSettings

  sealed trait FipsMode
  object FipsMode {
    case object NonFips extends FipsMode
    case object SslOnly extends FipsMode
  }

}

private object SslDecoders extends RequestIdAwareLogging {

  object consts {
    val rorSection: NonEmptyString = NonEmptyString.unsafeFrom("readonlyrest")
    val fipsMode: NonEmptyString = NonEmptyString.unsafeFrom("fips_mode")
    val externalSsl: NonEmptyString = NonEmptyString.unsafeFrom("ssl")
    val internodeSsl: NonEmptyString = NonEmptyString.unsafeFrom("ssl_internode")
    val keystoreFile: NonEmptyString = NonEmptyString.unsafeFrom("keystore_file")
    val keystorePass: NonEmptyString = NonEmptyString.unsafeFrom("keystore_pass")
    val truststoreFile: NonEmptyString = NonEmptyString.unsafeFrom("truststore_file")
    val truststorePass: NonEmptyString = NonEmptyString.unsafeFrom("truststore_pass")
    val keyPass: NonEmptyString = NonEmptyString.unsafeFrom("key_pass")
    val keyAlias: NonEmptyString = NonEmptyString.unsafeFrom("key_alias")
    val allowedCiphers: NonEmptyString = NonEmptyString.unsafeFrom("allowed_ciphers")
    val allowedProtocols: NonEmptyString = NonEmptyString.unsafeFrom("allowed_protocols")
    val certificateVerification: NonEmptyString = NonEmptyString.unsafeFrom("certificate_verification")
    val hostnameVerification: NonEmptyString = NonEmptyString.unsafeFrom("hostname_verification")
    val clientAuthentication: NonEmptyString = NonEmptyString.unsafeFrom("client_authentication")
    val verification: NonEmptyString = NonEmptyString.unsafeFrom("verification")
    val enable: NonEmptyString = NonEmptyString.unsafeFrom("enable")
    val serverCertificateKeyFile: NonEmptyString = NonEmptyString.unsafeFrom("server_certificate_key_file")
    val serverCertificateFile: NonEmptyString = NonEmptyString.unsafeFrom("server_certificate_file")
    val clientTrustedCertificateFile: NonEmptyString = NonEmptyString.unsafeFrom("client_trusted_certificate_file")
  }

  def rorSslDecoder(basePath: File)
                   (implicit systemContext: SystemContext): YamlLeafOrPropertyDecoder[Option[RorSslSettings]] = {
    for {
      fipsMode <- fipsModeDecoder
      externalSsl <- externalSslSectionDecoder(basePath, fipsMode.getOrElse(FipsMode.NonFips))
      internodeSsl <- internodeSslSectionDecoder(basePath, fipsMode.getOrElse(FipsMode.NonFips))
    } yield {
      (externalSsl, internodeSsl) match {
        case (Some(ex), Some(in)) => Some(RorSslSettings.ExternalAndInternodeSslSettings(ex, in))
        case (Some(ex), None) => Some(RorSslSettings.OnlyExternalSslSettings(ex))
        case (None, Some(in)) => Some(RorSslSettings.OnlyInternodeSslSettings(in))
        case (None, None) => None
      }
    }
  }

  private def fipsModeDecoder(implicit sc: SystemContext): YamlLeafOrPropertyDecoder[Option[FipsMode]] = {
    implicit val propertiesProvider: PropertiesProvider = sc.propertiesProvider
    val decoder: FromString[FipsMode] = FromString.instance {
      case "NON_FIPS" => Right(FipsMode.NonFips)
      case "SSL_ONLY" => Right(FipsMode.SslOnly)
      case other      => Left(s"Invalid settings option '${other.show}' for FIPS MODE. Valid values are: NON_FIPS, SSL_ONLY")
    }
    YamlLeafOrPropertyDecoder.createOptionalValueDecoder(
      path = NonEmptyList.of(consts.rorSection, consts.fipsMode),
      decoder = decoder
    )
  }

  private def externalSslSectionDecoder(basePath: File, fipsMode: FipsMode)
                                       (implicit sc: SystemContext): YamlLeafOrPropertyDecoder[Option[ExternalSslSettings]] = {
    implicit val pp: PropertiesProvider = sc.propertiesProvider
    val sectionPath = NonEmptyList.of(consts.rorSection, consts.externalSsl)
    YamlLeafOrPropertyDecoder.whenSectionPresent[ExternalSslSettings](sectionPath) {
      for {
        enable <- YamlLeafOrPropertyDecoder.optionalBooleanDecoder(sectionPath :+ consts.enable)
        result <- enable match {
          case Some(false) => YamlLeafOrPropertyDecoder.pure[Option[ExternalSslSettings]](None)
          case _ =>
            for {
              ciphers <- ciphersDecoder(sectionPath)
              protocols <- protocolsDecoder(sectionPath)
              clientAuthentication <- YamlLeafOrPropertyDecoder.optionalBooleanDecoder(sectionPath :+ consts.clientAuthentication)
              verification <- YamlLeafOrPropertyDecoder.optionalBooleanDecoder(sectionPath :+ consts.verification)
              keystoreFile <- keystoreFileDecoder(basePath, sectionPath)
              keystorePass <- YamlLeafOrPropertyDecoder.optionalStringDecoder(sectionPath :+ consts.keystorePass).map(_.map(KeystorePassword.apply))
              keyAlias <- YamlLeafOrPropertyDecoder.optionalStringDecoder(sectionPath :+ consts.keyAlias).map(_.map(KeyAlias.apply))
              keyPass <- YamlLeafOrPropertyDecoder.optionalStringDecoder(sectionPath :+ consts.keyPass).map(_.map(KeyPass.apply))
              truststoreFile <- truststoreFileDecoder(basePath, sectionPath)
              truststorePass <- YamlLeafOrPropertyDecoder.optionalStringDecoder(sectionPath :+ consts.truststorePass).map(_.map(TruststorePassword.apply))
              serverCertFile <- serverCertFileDecoder(basePath, sectionPath)
              serverCertKeyFile <- serverCertKeyFileDecoder(basePath, sectionPath)
              clientTrustedCertFile <- clientTrustedCertFileDecoder(basePath, sectionPath)
              r <- YamlLeafOrPropertyDecoder.fromEither[Option[ExternalSslSettings]] {
                for {
                  serverCert <- buildServerCertificateSettings(keystoreFile, keystorePass, keyAlias, keyPass, serverCertKeyFile, serverCertFile)
                  clientCert <- buildClientCertificateSettings(truststoreFile, truststorePass, clientTrustedCertFile)
                } yield Some(ExternalSslSettings(
                  serverCertificateSettings = serverCert,
                  clientCertificateSettings = clientCert,
                  allowedProtocols = protocols.getOrElse(Set.empty),
                  allowedCiphers = ciphers.getOrElse(Set.empty),
                  clientAuthenticationEnabled = clientAuthentication.orElse(verification).getOrElse(false),
                  fipsMode = fipsMode
                ))
              }
            } yield r
        }
      } yield result
    }
  }

  private def internodeSslSectionDecoder(basePath: File, fipsMode: FipsMode)
                                        (implicit sc: SystemContext): YamlLeafOrPropertyDecoder[Option[InternodeSslSettings]] = {
    implicit val pp: PropertiesProvider = sc.propertiesProvider
    val sectionPath = NonEmptyList.of(consts.rorSection, consts.internodeSsl)
    YamlLeafOrPropertyDecoder.whenSectionPresent[InternodeSslSettings](sectionPath) {
      for {
        enable <- YamlLeafOrPropertyDecoder.optionalBooleanDecoder(sectionPath :+ consts.enable)
        result <- enable match {
          case Some(false) =>
            YamlLeafOrPropertyDecoder.pure[Option[InternodeSslSettings]](None)
          case _ =>
            for {
              ciphers <- ciphersDecoder(sectionPath)
              protocols <- protocolsDecoder(sectionPath)
              clientAuthentication <- YamlLeafOrPropertyDecoder.optionalBooleanDecoder(sectionPath :+ consts.clientAuthentication)
              certificateVerification <- YamlLeafOrPropertyDecoder.optionalBooleanDecoder(sectionPath :+ consts.certificateVerification)
              hostnameVerification <- YamlLeafOrPropertyDecoder.optionalBooleanDecoder(sectionPath :+ consts.hostnameVerification)
              verification <- YamlLeafOrPropertyDecoder.optionalBooleanDecoder(sectionPath :+ consts.verification)
              keystoreFile <- keystoreFileDecoder(basePath, sectionPath)
              keystorePass <- YamlLeafOrPropertyDecoder.optionalStringDecoder(sectionPath :+ consts.keystorePass).map(_.map(KeystorePassword.apply))
              keyAlias <- YamlLeafOrPropertyDecoder.optionalStringDecoder(sectionPath :+ consts.keyAlias).map(_.map(KeyAlias.apply))
              keyPass <- YamlLeafOrPropertyDecoder.optionalStringDecoder(sectionPath :+ consts.keyPass).map(_.map(KeyPass.apply))
              truststoreFile <- truststoreFileDecoder(basePath, sectionPath)
              truststorePass <- YamlLeafOrPropertyDecoder.optionalStringDecoder(sectionPath :+ consts.truststorePass).map(_.map(TruststorePassword.apply))
              serverCertFile <- serverCertFileDecoder(basePath, sectionPath)
              serverCertKeyFile <- serverCertKeyFileDecoder(basePath, sectionPath)
              clientTrustedCertFile <- clientTrustedCertFileDecoder(basePath, sectionPath)
              r <- YamlLeafOrPropertyDecoder.fromEither[Option[InternodeSslSettings]] {
                for {
                  serverCert <- buildServerCertificateSettings(keystoreFile, keystorePass, keyAlias, keyPass, serverCertKeyFile, serverCertFile)
                  clientCert <- buildClientCertificateSettings(truststoreFile, truststorePass, clientTrustedCertFile)
                } yield Some(InternodeSslSettings(
                  serverCertificateSettings = serverCert,
                  clientCertificateSettings = clientCert,
                  allowedProtocols = protocols.getOrElse(Set.empty),
                  allowedCiphers = ciphers.getOrElse(Set.empty),
                  clientAuthenticationEnabled = clientAuthentication.getOrElse(false),
                  certificateVerificationEnabled = certificateVerification.orElse(verification).getOrElse(false),
                  hostnameVerificationEnabled = hostnameVerification.getOrElse(false),
                  fipsMode = fipsMode
                ))
              }
            } yield r
        }
      } yield result
    }
  }

  private def buildServerCertificateSettings(keystoreFile: Option[KeystoreFile],
                                             keystorePass: Option[KeystorePassword],
                                             keyAlias: Option[KeyAlias],
                                             keyPass: Option[KeyPass],
                                             serverCertificateKeyFile: Option[ServerCertificateKeyFile],
                                             serverCertificateFile: Option[ServerCertificateFile]): Either[String, ServerCertificateSettings] = {
    val keystoreBased = keystoreFile.isDefined || keystorePass.isDefined || keyAlias.isDefined || keyPass.isDefined
    val fileBased = serverCertificateKeyFile.isDefined || serverCertificateFile.isDefined
    (keystoreBased, fileBased) match {
      case (true, true) =>
        val errorMessage = s"Field sets [${consts.serverCertificateKeyFile.show}, ${consts.serverCertificateFile.show}] and [${consts.keystoreFile.show}, ${consts.keystorePass.show}, ${consts.keyAlias.show}, ${consts.keyPass.show}] could not be present in the same settings section"
        noRequestIdLogger.error(errorMessage)
        Left(errorMessage)
      case (true, false) =>
        keystoreFile match {
          case Some(file) => Right(ServerCertificateSettings.KeystoreBasedSettings(file, keystorePass, keyAlias, keyPass))
          case None =>
            val errorMessage = s"'${consts.keystoreFile.show}' is required when keystore based SSL settings are used"
            noRequestIdLogger.error(errorMessage)
            Left(errorMessage)
        }
      case (false, true) =>
        if (!SSLCertHelper.isPEMHandlingAvailable) {
          val errorMessage = "PEM File Handling is not available in your current deployment of Elasticsearch"
          noRequestIdLogger.error(errorMessage)
          Left(errorMessage)
        } else {
          (serverCertificateKeyFile, serverCertificateFile) match {
            case (Some(keyFile), Some(certFile)) => Right(ServerCertificateSettings.FileBasedSettings(keyFile, certFile))
            case _ =>
              val errorMessage = s"'${consts.serverCertificateKeyFile.show}' and '${consts.serverCertificateFile.show}' are both required when file based SSL settings are used"
              noRequestIdLogger.error(errorMessage)
              Left(errorMessage)
          }
        }
      case (false, false) =>
        val errorMessage = "There was no SSL settings present for server"
        noRequestIdLogger.error(errorMessage)
        Left(errorMessage)
    }
  }

  private def buildClientCertificateSettings(truststoreFile: Option[TruststoreFile],
                                             truststorePassword: Option[TruststorePassword],
                                             clientTrustedCertificateFile: Option[ClientTrustedCertificateFile]): Either[String, Option[ClientCertificateSettings]] = {
    val truststoreBased = truststoreFile.isDefined || truststorePassword.isDefined
    val fileBased = clientTrustedCertificateFile.isDefined
    (truststoreBased, fileBased) match {
      case (true, true) =>
        val errorMessage = s"Field sets [${consts.clientTrustedCertificateFile.show}] and [${consts.truststoreFile.show}, ${consts.truststorePass.show}] could not be present in the same settings section"
        noRequestIdLogger.error(errorMessage)
        Left(errorMessage)
      case (true, false) =>
        truststoreFile match {
          case Some(file) => Right(Some(ClientCertificateSettings.TruststoreBasedSettings(file, truststorePassword)))
          case None =>
            val errorMessage = s"'${consts.truststoreFile.show}' is required when truststore based client SSL settings are used"
            noRequestIdLogger.error(errorMessage)
            Left(errorMessage)
        }
      case (false, true) =>
        if (!SSLCertHelper.isPEMHandlingAvailable) {
          val errorMessage = "PEM File Handling is not available in your current deployment of Elasticsearch"
          noRequestIdLogger.error(errorMessage)
          Left(errorMessage)
        } else {
          clientTrustedCertificateFile match {
            case Some(file) => Right(Some(ClientCertificateSettings.FileBasedSettings(file)))
            case None => Right(None)
          }
        }
      case (false, false) =>
        Right(None)
    }
  }

  private def fileDecoder(basePath: File, sectionPath: NonEmptyList[NonEmptyString], key: NonEmptyString)
                         (implicit sc: SystemContext): YamlLeafOrPropertyDecoder[Option[File]] = {
    implicit val propertiesProvider: PropertiesProvider = sc.propertiesProvider
    YamlLeafOrPropertyDecoder.createOptionalValueDecoder(sectionPath :+ key, FromString.string.map(basePath / _))
  }

  private def keystoreFileDecoder(basePath: File, sectionPath: NonEmptyList[NonEmptyString])
                                 (implicit sc: SystemContext): YamlLeafOrPropertyDecoder[Option[KeystoreFile]] = {
    fileDecoder(basePath, sectionPath, consts.keystoreFile).map(_.map(KeystoreFile.apply))
  }

  private def truststoreFileDecoder(basePath: File, sectionPath: NonEmptyList[NonEmptyString])
                                   (implicit sc: SystemContext): YamlLeafOrPropertyDecoder[Option[TruststoreFile]] = {
    fileDecoder(basePath, sectionPath, consts.truststoreFile).map(_.map(TruststoreFile.apply))
  }

  private def serverCertFileDecoder(basePath: File, sectionPath: NonEmptyList[NonEmptyString])
                                   (implicit sc: SystemContext): YamlLeafOrPropertyDecoder[Option[ServerCertificateFile]] = {
    fileDecoder(basePath, sectionPath, consts.serverCertificateFile).map(_.map(ServerCertificateFile.apply))
  }

  private def serverCertKeyFileDecoder(basePath: File, sectionPath: NonEmptyList[NonEmptyString])
                                      (implicit sc: SystemContext): YamlLeafOrPropertyDecoder[Option[ServerCertificateKeyFile]] = {
    fileDecoder(basePath, sectionPath, consts.serverCertificateKeyFile).map(_.map(ServerCertificateKeyFile.apply))
  }

  private def clientTrustedCertFileDecoder(basePath: File, sectionPath: NonEmptyList[NonEmptyString])
                                          (implicit sc: SystemContext): YamlLeafOrPropertyDecoder[Option[ClientTrustedCertificateFile]] = {
    fileDecoder(basePath, sectionPath, consts.clientTrustedCertificateFile).map(_.map(ClientTrustedCertificateFile.apply))
  }

  private def ciphersDecoder(sectionPath: NonEmptyList[NonEmptyString])
                            (implicit sc: SystemContext): YamlLeafOrPropertyDecoder[Option[Set[Cipher]]] = {
    implicit val propertiesProvider: PropertiesProvider = sc.propertiesProvider
    YamlLeafOrPropertyDecoder.createOptionalListValueDecoder(
      path = sectionPath :+ consts.allowedCiphers,
      itemDecoder = FromString.string.map(Cipher.apply)
    )
  }

  private def protocolsDecoder(sectionPath: NonEmptyList[NonEmptyString])
                              (implicit sc: SystemContext): YamlLeafOrPropertyDecoder[Option[Set[Protocol]]] = {
    implicit val propertiesProvider: PropertiesProvider = sc.propertiesProvider
    YamlLeafOrPropertyDecoder.createOptionalListValueDecoder(
      path = sectionPath :+ consts.allowedProtocols,
      itemDecoder = FromString.string.map(Protocol.apply)
    )
  }
}
