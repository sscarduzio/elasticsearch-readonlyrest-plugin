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
package tech.beshu.ror.accesscontrol.factory.decoders

import cats.data.NonEmptyList
import io.circe.{Decoder, DecodingFailure, HCursor}
import io.lemonlabs.uri.Uri
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.audit.AuditingTool
import tech.beshu.ror.accesscontrol.audit.AuditingTool.Settings.AuditSink
import tech.beshu.ror.accesscontrol.audit.AuditingTool.Settings.AuditSink.Config
import tech.beshu.ror.accesscontrol.audit.AuditingTool.Settings.AuditSink.Config.{EsIndexBasedSink, LogBasedSink}
import tech.beshu.ror.accesscontrol.domain.RorAuditIndexTemplate.CreationError
import tech.beshu.ror.accesscontrol.domain.{AuditCluster, RorAuditIndexTemplate, RorAuditLoggerName}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.AuditingSettingsCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.decoders.common.{lemonLabsUriDecoder, nonEmptyStringDecoder}
import tech.beshu.ror.accesscontrol.utils.CirceOps.DecodingFailureOps
import tech.beshu.ror.accesscontrol.utils.SyncDecoderCreator
import tech.beshu.ror.audit.AuditLogSerializer
import tech.beshu.ror.audit.adapters.DeprecatedAuditLogSerializerAdapter
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.yaml.YamlKeyDecoder

import scala.annotation.nowarn
import scala.util.{Failure, Success, Try}

object AuditingSettingsDecoder extends Logging {

  lazy val instance: Decoder[Option[AuditingTool.Settings]] = {
    for {
      auditSettings <- auditSettingsDecoder
      deprecatedAuditSettings <- DeprecatedAuditSettingsDecoder.instance
    } yield auditSettings.orElse(deprecatedAuditSettings)
  }

  private val auditSettingsDecoder: Decoder[Option[AuditingTool.Settings]] = Decoder.instance { c =>
    for {
      isAuditEnabled <- YamlKeyDecoder[Boolean](
        segments = NonEmptyList.of("audit", "enabled"),
        default = false
      ).apply(c)
      result <- if (isAuditEnabled) {
        decodeAuditSettings(c).map(Some.apply)
      } else {
        Right(None)
      }
    } yield result
  }

  private def decodeAuditSettings = {
    decodeAuditSettingsWith(auditSinkConfigSimpleDecoder)
      .handleErrorWith { error =>
        if (error.aclCreationError.isDefined) {
          // the schema was valid, but the config not
          Decoder.failed(error)
        } else {
          decodeAuditSettingsWith(auditSinkConfigExtendedDecoder)
        }
      }
  }

  private def decodeAuditSettingsWith(implicit sinkDecoder: Decoder[AuditSink]) = {
    SyncDecoderCreator
      .instance {
        _.downField("audit").downField("outputs").as[Option[List[AuditSink]]]
      }
      .emapE {
        case Some(outputs) =>
          NonEmptyList
            .fromList(outputs.distinct)
            .map(AuditingTool.Settings.apply)
            .toRight(AuditingSettingsCreationError(Message(s"The audit 'outputs' array cannot be empty")))
        case None =>
          AuditingTool.Settings(
            NonEmptyList.of(AuditSink.Enabled(EsIndexBasedSink.default))
          ).asRight
      }
      .decoder
  }

  private val auditSinkConfigSimpleDecoder: Decoder[AuditSink] = {
    SyncDecoderCreator
      .from(Decoder.decodeString)
      .emapE[AuditSink] {
        case "index" =>
          AuditSink.Enabled(Config.EsIndexBasedSink.default).asRight
        case "log" =>
          AuditSink.Enabled(Config.LogBasedSink.default).asRight
        case other =>
          unsupportedOutputTypeError(
            unsupportedType = other,
            supportedTypes = List("index", "log")
          ).asLeft
      }
      .decoder
  }

  private val auditSinkConfigExtendedDecoder: Decoder[AuditSink] = {
    implicit val loggerNameDecoder: Decoder[RorAuditLoggerName] = {
      SyncDecoderCreator
        .from(nonEmptyStringDecoder)
        .map(RorAuditLoggerName.apply)
        .withError(AuditingSettingsCreationError(Message("The audit 'logger_name' cannot be empty")))
        .decoder
    }

    implicit val logBasedSinkConfigDecoder: Decoder[LogBasedSink] = Decoder.instance { c =>
      for {
        logSerializer <- c.downField("serializer").as[Option[AuditLogSerializer]]
        loggerName <- c.downField("logger_name").as[Option[RorAuditLoggerName]]
      } yield LogBasedSink(
        logSerializer = logSerializer.getOrElse(LogBasedSink.default.logSerializer),
        loggerName = loggerName.getOrElse(LogBasedSink.default.loggerName)
      )
    }

    implicit val indexBasedAuditSinkDecoder: Decoder[EsIndexBasedSink] = Decoder.instance { c =>
      for {
        auditIndexTemplate <- c.downField("index_template").as[Option[RorAuditIndexTemplate]]
        customAuditSerializer <- c.downField("serializer").as[Option[AuditLogSerializer]]
        remoteAuditCluster <- c.downField("cluster").as[Option[AuditCluster.RemoteAuditCluster]]
        enableReportingEsNodeDetails <- c.downField("enable_reporting_es_node_details").as[Option[Boolean]]
      } yield EsIndexBasedSink(
        customAuditSerializer.getOrElse(EsIndexBasedSink.default.logSerializer),
        auditIndexTemplate.getOrElse(EsIndexBasedSink.default.rorAuditIndexTemplate),
        remoteAuditCluster.getOrElse(EsIndexBasedSink.default.auditCluster),
        enableReportingEsNodeDetails.getOrElse(EsIndexBasedSink.default.enableReportingEsNodeDetails)
      )
    }

    Decoder
      .instance[AuditSink] { c =>
        for {
          sinkType <- c.downField("type").as[String]
          sinkConfig <- sinkType match {
            case "index" => c.as[EsIndexBasedSink]
            case "log" => c.as[LogBasedSink]
            case other => toDecodingFailure(unsupportedOutputTypeError(
              unsupportedType = other,
              supportedTypes = List("index", "log")
            )).asLeft
          }
          isSinkEnabled <- c.downField("enabled").as[Option[Boolean]]
        } yield {
          if (isSinkEnabled.getOrElse(true)) {
            AuditSink.Enabled(sinkConfig)
          } else {
            AuditSink.Disabled
          }
        }
      }
  }

  private implicit val rorAuditIndexTemplateDecoder: Decoder[RorAuditIndexTemplate] =
    SyncDecoderCreator
      .from(Decoder.decodeString)
      .emapE { patternStr =>
        RorAuditIndexTemplate
          .from(patternStr)
          .left
          .map {
            case CreationError.ParsingError(msg) =>
              AuditingSettingsCreationError(Message(
                s"Illegal pattern specified for audit_index_template. Have you misplaced quotes? Search for 'DateTimeFormatter patterns' to learn the syntax. Pattern was: ${patternStr.show} error: ${msg.show}"
              ))
          }
      }
      .decoder

  @nowarn("cat=deprecation")
  private implicit val customAuditLogSerializer: Decoder[AuditLogSerializer] =
    SyncDecoderCreator
      .from(Decoder.decodeString)
      .emapE { fullClassName =>
        Try {
          Class.forName(fullClassName).getDeclaredConstructor().newInstance() match {
            case serializer: tech.beshu.ror.audit.AuditLogSerializer =>
              Some(serializer)
            case serializer: tech.beshu.ror.requestcontext.AuditLogSerializer[_] =>
              Some(new DeprecatedAuditLogSerializerAdapter(serializer))
            case _ => None
          }
        } match {
          case Success(Some(customSerializer)) =>
            logger.info(s"Using custom serializer: ${customSerializer.getClass.getName}")
            Right(customSerializer)
          case Success(None) => Left(AuditingSettingsCreationError(Message(s"Class ${fullClassName.show} is not a subclass of ${classOf[AuditLogSerializer].getName.show} or ${classOf[tech.beshu.ror.requestcontext.AuditLogSerializer[_]].getName.show}")))
          case Failure(ex) => Left(AuditingSettingsCreationError(Message(s"Cannot create instance of class '${fullClassName.show}', error: ${ex.getMessage.show}")))
        }
      }
      .decoder

  private implicit val remoteAuditClusterDecoder: Decoder[AuditCluster.RemoteAuditCluster] =
    SyncDecoderCreator
      .from(Decoder.decodeNonEmptyList[Uri])
      .withError(AuditingSettingsCreationError(Message("Non empty list of valid URI is required")))
      .map(AuditCluster.RemoteAuditCluster.apply)
      .decoder

  private def toDecodingFailure(error: CoreCreationError) = {
    DecodingFailure("", Nil)
      .overrideDefaultErrorWith(error)
  }

  private def unsupportedOutputTypeError(unsupportedType: String,
                                         supportedTypes: List[String]) = {
    AuditingSettingsCreationError(Message(
      s"Unsupported 'type' of audit output: ${unsupportedType.show}. Supported types: [${supportedTypes.show}]"
    ))
  }

  private object DeprecatedAuditSettingsDecoder {
    lazy val instance: Decoder[Option[AuditingTool.Settings]] = Decoder.instance { c =>
      whenEnabled(c) {
        for {
          auditIndexTemplate <- decodeOptionalSetting[RorAuditIndexTemplate](c)("index_template", fallbackKey = "audit_index_template")
          customAuditSerializer <- decodeOptionalSetting[AuditLogSerializer](c)("serializer", fallbackKey = "audit_serializer")
          remoteAuditCluster <- decodeOptionalSetting[AuditCluster.RemoteAuditCluster](c)("cluster", fallbackKey = "audit_cluster")
          enableReportingEsNodeDetails <- c.downField("enable_reporting_es_node_details").as[Option[Boolean]]
        } yield AuditingTool.Settings(
          auditSinks = NonEmptyList.one(
            AuditSink.Enabled(
              EsIndexBasedSink(
                logSerializer = customAuditSerializer.getOrElse(EsIndexBasedSink.default.logSerializer),
                rorAuditIndexTemplate = auditIndexTemplate.getOrElse(EsIndexBasedSink.default.rorAuditIndexTemplate),
                auditCluster = remoteAuditCluster.getOrElse(EsIndexBasedSink.default.auditCluster),
                enableReportingEsNodeDetails = enableReportingEsNodeDetails.getOrElse(EsIndexBasedSink.default.enableReportingEsNodeDetails),
              )
            )
          )
        )
      }
    }

    private def whenEnabled(cursor: HCursor)(decoding: => Decoder.Result[AuditingTool.Settings]) = {
      for {
        isEnabled <- decodeOptionalSetting[Boolean](cursor)("collector", fallbackKey = "audit_collector")
        result <- if (isEnabled.getOrElse(false)) decoding.map(Some.apply) else Right(None)
      } yield result
    }

    private def decodeOptionalSetting[T: Decoder](cursor: HCursor)(key: String, fallbackKey: String): Decoder.Result[Option[T]] = {
      cursor.downField("audit").get[Option[T]](key)
        .flatMap {
          case Some(value) => Right(Some(value))
          case None => cursor.get[Option[T]](fallbackKey)
        }
    }
  }
}
