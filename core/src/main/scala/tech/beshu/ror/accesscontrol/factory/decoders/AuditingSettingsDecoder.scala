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
import io.circe.Decoder.*
import io.circe.{Decoder, DecodingFailure, HCursor, KeyDecoder}
import io.lemonlabs.uri.Uri
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.audit.AuditingTool
import tech.beshu.ror.accesscontrol.audit.AuditingTool.AuditSettings.AuditSink
import tech.beshu.ror.accesscontrol.audit.AuditingTool.AuditSettings.AuditSink.Config
import tech.beshu.ror.accesscontrol.audit.AuditingTool.AuditSettings.AuditSink.Config.{EsDataStreamBasedSink, EsIndexBasedSink, LogBasedSink}
import tech.beshu.ror.accesscontrol.audit.configurable.{AuditFieldValueDeserializer, ConfigurableAuditLogSerializer}
import tech.beshu.ror.accesscontrol.domain.RorAuditIndexTemplate.CreationError
import tech.beshu.ror.accesscontrol.domain.{AuditCluster, RorAuditDataStream, RorAuditIndexTemplate, RorAuditLoggerName}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.{AuditingSettingsCreationError, Reason}
import tech.beshu.ror.accesscontrol.factory.decoders.common.{lemonLabsUriDecoder, nonEmptyStringDecoder}
import tech.beshu.ror.accesscontrol.utils.CirceOps.{AclCreationErrorCoders, DecodingFailureOps}
import tech.beshu.ror.accesscontrol.utils.SyncDecoderCreator
import tech.beshu.ror.audit.AuditSerializationHelper.AllowedEventSerializationMode.*
import tech.beshu.ror.audit.AuditSerializationHelper.AuditFieldName
import tech.beshu.ror.audit.adapters.*
import tech.beshu.ror.audit.{AuditEnvironmentContext, AuditFieldValue, AuditLogSerializer}
import tech.beshu.ror.es.EsVersion
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.yaml.YamlKeyDecoder

import scala.annotation.nowarn
import scala.util.{Failure, Success, Try}

object AuditingSettingsDecoder extends Logging {

  def instance(esVersion: EsVersion)
              (implicit context: AuditEnvironmentContext): Decoder[Option[AuditingTool.AuditSettings]] = {
    for {
      auditSettings <- auditSettingsDecoder(esVersion)
      deprecatedAuditSettings <- DeprecatedAuditSettingsDecoder.instance
    } yield auditSettings.orElse(deprecatedAuditSettings)
  }

  private def auditSettingsDecoder(esVersion: EsVersion)
                                  (using AuditEnvironmentContext): Decoder[Option[AuditingTool.AuditSettings]] = Decoder.instance { c =>
    for {
      isAuditEnabled <- YamlKeyDecoder[Boolean](
        segments = NonEmptyList.of("audit", "enabled"),
        default = false
      ).apply(c)
      result <- if (isAuditEnabled) {
        decodeAuditSettings(using esVersion, summon[AuditEnvironmentContext])(c).map(Some.apply)
      } else {
        Right(None)
      }
    } yield result
  }

  private def decodeAuditSettings(using EsVersion, AuditEnvironmentContext) = {
    decodeAuditSettingsWith(using auditSinkConfigSimpleDecoder, summon[AuditEnvironmentContext])
      .handleErrorWith { error =>
        if (error.aclCreationError.isDefined) {
          // the schema was valid, but the config not
          Decoder.failed(error)
        } else {
          decodeAuditSettingsWith(using auditSinkConfigExtendedDecoder, summon[AuditEnvironmentContext])
        }
      }
  }

  private def decodeAuditSettingsWith(using Decoder[AuditSink], AuditEnvironmentContext) = {
    SyncDecoderCreator
      .instance {
        _.downField("audit").downField("outputs").as[Option[List[AuditSink]]]
      }
      .emapE {
        case Some(outputs) =>
          NonEmptyList
            .fromList(outputs.distinct)
            .map(AuditingTool.AuditSettings.apply)
            .toRight(AuditingSettingsCreationError(Message(s"The audit 'outputs' array cannot be empty")))
        case None =>
          AuditingTool.AuditSettings(
            NonEmptyList.of(AuditSink.Enabled(EsIndexBasedSink.default))
          ).asRight
      }
      .decoder
  }

  private def auditSinkConfigSimpleDecoder(using EsVersion, AuditEnvironmentContext): Decoder[AuditSink] = {
    Decoder[AuditSinkType]
      .emap[AuditSink.Config] {
        case AuditSinkType.DataStream =>
          Config.EsDataStreamBasedSink.default.asRight
        case AuditSinkType.Index =>
          Config.EsIndexBasedSink.default.asRight
        case AuditSinkType.Log =>
          Config.LogBasedSink.default.asRight
      }
      .map(AuditSink.Enabled.apply)
  }

  private def auditSinkConfigExtendedDecoder(using EsVersion, AuditEnvironmentContext): Decoder[AuditSink] = {
    given Decoder[RorAuditLoggerName] = {
      SyncDecoderCreator
        .from(nonEmptyStringDecoder)
        .map(RorAuditLoggerName.apply)
        .withError(AuditingSettingsCreationError(Message("The audit 'logger_name' cannot be empty")))
        .decoder
    }

    given Decoder[LogBasedSink] = Decoder.instance { c =>
      for {
        logSerializer <- c.as[Option[AuditLogSerializer]]
        loggerName <- c.downField("logger_name").as[Option[RorAuditLoggerName]]
      } yield LogBasedSink(
        logSerializer = logSerializer.getOrElse(LogBasedSink.default.logSerializer),
        loggerName = loggerName.getOrElse(LogBasedSink.default.loggerName)
      )
    }

    given Decoder[EsIndexBasedSink] = Decoder.instance { c =>
      for {
        auditIndexTemplate <- c.downField("index_template").as[Option[RorAuditIndexTemplate]]
        logSerializer <- c.as[Option[AuditLogSerializer]]
        remoteAuditCluster <- c.downField("cluster").as[Option[AuditCluster.RemoteAuditCluster]]
      } yield EsIndexBasedSink(
        logSerializer.getOrElse(EsIndexBasedSink.default.logSerializer),
        auditIndexTemplate.getOrElse(EsIndexBasedSink.default.rorAuditIndexTemplate),
        remoteAuditCluster.getOrElse(EsIndexBasedSink.default.auditCluster),
      )
    }

    given Decoder[EsDataStreamBasedSink] = Decoder.instance { c =>
      for {
        rorAuditDataStream <- c.downField("data_stream").as[Option[RorAuditDataStream]]
        logSerializer <- c.as[Option[AuditLogSerializer]]
        remoteAuditCluster <- c.downField("cluster").as[Option[AuditCluster.RemoteAuditCluster]]
      } yield EsDataStreamBasedSink(
        logSerializer.getOrElse(EsDataStreamBasedSink.default.logSerializer),
        rorAuditDataStream.getOrElse(EsDataStreamBasedSink.default.rorAuditDataStream),
        remoteAuditCluster.getOrElse(EsDataStreamBasedSink.default.auditCluster),
      )
    }

    Decoder
      .instance[AuditSink] { c =>
        for {
          sinkType <- c.downField("type").as[AuditSinkType]
          sinkConfig <- sinkType match {
            case AuditSinkType.DataStream =>


              c.as[EsDataStreamBasedSink]
            case AuditSinkType.Index => c.as[EsIndexBasedSink]
            case AuditSinkType.Log => c.as[LogBasedSink]
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

  private given Decoder[RorAuditDataStream] =
    SyncDecoderCreator
      .from(common.nonEmptyStringDecoder)
      .emapE { patternStr =>
        RorAuditDataStream(patternStr)
          .leftMap {
            case RorAuditDataStream.CreationError.FormatError(msg) =>
              AuditingSettingsCreationError(Message(
                s"Illegal format for ROR audit 'data_stream' name - ${msg.show}"
              ))
          }
      }
      .decoder

  private given Decoder[RorAuditIndexTemplate] =
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

  given auditLogSerializerDecoder(using context: AuditEnvironmentContext): Decoder[Option[AuditLogSerializer]] = SyncDecoderCreator.from(
      Decoder.instance[Option[AuditLogSerializer]] { c =>
        for {
          configurable <- c.downField("configurable").as[Option[Boolean]]
          serializer <- if (configurable.contains(true)) {
            for {
              serializeAllAllowedEvents <- c.downField("serialize_all_allowed_events").as[Boolean]
                .left.map(withAuditingSettingsCreationErrorMessage("Configurable serializer is used, but the serialize_all_allowed_events setting is missing in configuration"))
              allowedEventSerializationMode = if (serializeAllAllowedEvents) SerializeAllAllowedEvents else SerializeOnlyAllowedEventsWithInfoLevelVerbose
              fields <- c.downField("fields").as[Map[AuditFieldName, AuditFieldValue]]
                .left.map(withAuditingSettingsCreationErrorMessage("Configurable serializer is used, but the fields setting is missing in configuration"))
              serializer = new ConfigurableAuditLogSerializer(context, allowedEventSerializationMode, fields)
            } yield Some(serializer)
          } else {
            for {
              fullClassNameOpt <- c.downField("serializer").as[Option[String]]
              legacyFullClassNameOpt <- c.downField("audit_serializer").as[Option[String]]
              serializerOpt <- fullClassNameOpt.orElse(legacyFullClassNameOpt) match {
                case Some(fullClassName) =>
                  createSerializerInstanceFromClassName(fullClassName).map(Some(_))
                    .left.map(error => DecodingFailure(AclCreationErrorCoders.stringify(error), Nil))
                case None =>
                  Right(None)
              }
            } yield serializerOpt
          }
        } yield serializer
      }
    )
    .decoder

  private def withAuditingSettingsCreationErrorMessage(message: String)(decodingFailure: DecodingFailure) = {
    decodingFailure.withMessage(AclCreationErrorCoders.stringify(AuditingSettingsCreationError(Message(message))))
  }

  @nowarn("cat=deprecation")
  private def createSerializerInstanceFromClassName(fullClassName: String)
                                                   (using context: AuditEnvironmentContext): Either[AuditingSettingsCreationError, AuditLogSerializer] = {
    val clazz = Class.forName(fullClassName)

    def createInstanceOfEnvironmentAwareSerializer(): Try[Any] =
      Try(clazz.getConstructor(classOf[AuditEnvironmentContext])).map(_.newInstance(context))

    def createInstanceOfSimpleSerializer(): Try[Any] =
      Try(clazz.getDeclaredConstructor()).map(_.newInstance())

    val serializer =
      createInstanceOfEnvironmentAwareSerializer()
        .orElse(createInstanceOfSimpleSerializer())
        .getOrElse(
          throw new IllegalStateException(
            s"Class ${Class.forName(fullClassName).getName} is required to have either one (AuditEnvironmentContext) parameter constructor or constructor without parameters"
          )
        )

    Try {
      serializer match {
        case serializer: tech.beshu.ror.audit.AuditLogSerializer =>
          Some(serializer)
        case serializer: tech.beshu.ror.audit.EnvironmentAwareAuditLogSerializer =>
          Some(new EnvironmentAwareAuditLogSerializerAdapter(serializer, summon[AuditEnvironmentContext]))
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

  given auditFieldNameDecoder: KeyDecoder[AuditFieldName] = {
    KeyDecoder.decodeKeyString.map(AuditFieldName.apply)
  }

  given auditFieldValueDecoder: Decoder[AuditFieldValue] = {
    SyncDecoderCreator
      .from(Decoder.decodeString)
      .emap(AuditFieldValueDeserializer.deserialize)
      .decoder
  }

  private given Decoder[AuditCluster.RemoteAuditCluster] =
    SyncDecoderCreator
      .from(Decoder.decodeNonEmptyList[Uri])
      .withError(AuditingSettingsCreationError(Message("Non empty list of valid URI is required")))
      .map(AuditCluster.RemoteAuditCluster.apply)
      .decoder


  private sealed trait AuditSinkType

  private object AuditSinkType {
    case object DataStream extends AuditSinkType

    case object Index extends AuditSinkType

    case object Log extends AuditSinkType

    def from(value: String, esVersion: EsVersion): Either[AuditingSettingsCreationError, AuditSinkType] = value match {
      case "data_stream" if esVersion >= dataStreamSupportEsVersion =>
        AuditSinkType.DataStream.asRight
      case "data_stream" =>
        Left(AuditingSettingsCreationError(Reason.Message(
          s"Data stream audit output is supported from Elasticsearch version ${dataStreamSupportEsVersion.formatted}, " +
            s"but your version is ${esVersion.formatted}. Use 'index' type or upgrade to ${dataStreamSupportEsVersion.formatted} or later."
        )))
      case "index" =>
        AuditSinkType.Index.asRight
      case "log" =>
        AuditSinkType.Log.asRight
      case other =>
        unsupportedOutputTypeError(
          unsupportedType = other,
          supportedTypes =
            if (esVersion >= dataStreamSupportEsVersion) {
              NonEmptyList.of("data_stream", "index", "log")
            } else {
              NonEmptyList.of("index", "log")
            }
        ).asLeft
    }

    private def unsupportedOutputTypeError(unsupportedType: String,
                                           supportedTypes: NonEmptyList[String]) = {
      AuditingSettingsCreationError(Message(
        s"Unsupported 'type' of audit output: ${unsupportedType.show}. Supported types: [${supportedTypes.toList.show}]"
      ))
    }

    given auditSinkTypeDecoder(using esVersion: EsVersion): Decoder[AuditSinkType] = {
      SyncDecoderCreator
        .from(Decoder.decodeString)
        .emapE(AuditSinkType.from(_, esVersion))
        .decoder
    }

    private val dataStreamSupportEsVersion = EsVersion(7, 9, 0)
  }

  private object DeprecatedAuditSettingsDecoder {
    def instance(using AuditEnvironmentContext): Decoder[Option[AuditingTool.AuditSettings]] = Decoder.instance { c =>
      whenEnabled(c) {
        for {
          auditIndexTemplate <- decodeOptionalSetting[RorAuditIndexTemplate](c)("index_template", fallbackKey = "audit_index_template")
          logSerializerOutsideAuditSection <- c.as[Option[AuditLogSerializer]]
          logSerializerInAuditSection <- c.downField("audit").success.map(_.as[Option[AuditLogSerializer]]).getOrElse(Right(None))
          logSerializer = logSerializerOutsideAuditSection.orElse(logSerializerInAuditSection)
          remoteAuditCluster <- decodeOptionalSetting[AuditCluster.RemoteAuditCluster](c)("cluster", fallbackKey = "audit_cluster")
        } yield AuditingTool.AuditSettings(
          auditSinks = NonEmptyList.one(
            AuditSink.Enabled(
              EsIndexBasedSink(
                logSerializer = logSerializer.getOrElse(EsIndexBasedSink.default.logSerializer),
                rorAuditIndexTemplate = auditIndexTemplate.getOrElse(EsIndexBasedSink.default.rorAuditIndexTemplate),
                auditCluster = remoteAuditCluster.getOrElse(EsIndexBasedSink.default.auditCluster),
              )
            )
          )
        )
      }
    }

    private def whenEnabled(cursor: HCursor)(decoding: => Decoder.Result[AuditingTool.AuditSettings]) = {
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
