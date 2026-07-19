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
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.refineV
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.*
import io.circe.Decoder.*
import io.lemonlabs.uri.Uri
import tech.beshu.ror.accesscontrol.audit.AuditingTool.*
import tech.beshu.ror.accesscontrol.audit.AuditingTool.AuditOutputsConfig.AuditOutput
import tech.beshu.ror.accesscontrol.audit.AuditingTool.AuditOutputsConfig.AuditOutput.*
import tech.beshu.ror.accesscontrol.audit.AuditingTool.Mode
import tech.beshu.ror.accesscontrol.audit.configurable.AuditFieldValueDescriptorParser
import tech.beshu.ror.accesscontrol.audit.{AuditSerializer, AuditingTool, JsonAuditSerializer}
import tech.beshu.ror.accesscontrol.domain.AuditCluster.{
  AuditClusterNode,
  ClusterMode,
  NodeCredentials,
  RemoteAuditCluster
}
import tech.beshu.ror.accesscontrol.domain.RorAuditIndexTemplate.CreationError
import tech.beshu.ror.accesscontrol.domain.{
  AuditCluster,
  RorAuditDataStream,
  RorAuditIndexTemplate,
  RorAuditLoggerName,
  SinkName
}
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.AuditingSettingsCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.decoders.common.{lemonLabsUriDecoder, nonEmptyStringDecoder}
import tech.beshu.ror.accesscontrol.utils.CirceOps.*
import tech.beshu.ror.accesscontrol.utils.SyncDecoderCreator
import tech.beshu.ror.audit.AuditLogSerializer
import tech.beshu.ror.audit.AuditResponseContext.Verbosity
import tech.beshu.ror.audit.adapters.*
import tech.beshu.ror.audit.utils.AuditSerializationHelper.{AllowedEventMode, AuditFieldPath, AuditFieldValueDescriptor}
import tech.beshu.ror.constants.EsFeatureVersions
import tech.beshu.ror.es.EsEnv
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.FromString
import tech.beshu.ror.utils.RequestIdAwareLogging
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import scala.annotation.nowarn
import scala.util.{Failure, Success, Try}

object AuditingSettingsDecoder extends RequestIdAwareLogging {

  def standard(esEnv: EsEnv): Decoder[AuditingConfig.Standard] =
    makeDecoder(esEnv, decodeStandardAuditSettings)

  def legacy(esEnv: EsEnv): Decoder[AuditingConfig.Legacy] =
    makeDecoder(esEnv, decodeLegacyAuditSettings)

  private def makeDecoder[M >: Mode.Both <: Mode](
      esEnv: EsEnv,
      specificDecoder: Decoder[AuditOutputsConfig[M]],
  ): Decoder[AuditingConfig[M]] =
    for {
      auditSettings <- auditSettingsDecoder[M](specificDecoder)
      deprecatedAuditSettings <- DeprecatedAuditSettingsDecoder.instance
      defaultAclLog <- defaultAclLogDecoder
    } yield AuditingConfig(
      outputsConfig = auditSettings.orElse(deprecatedAuditSettings),
      defaultAclLog = defaultAclLog,
      esNodeSettings = esEnv.esNodeSettings,
    )

  private def defaultAclLogDecoder: Decoder[Boolean] = Decoder.instance { c =>
    val nested = c.downField("audit").downField("default_acl_log_enabled")
    val flat = c.downField("audit.default_acl_log_enabled")
    (nested.focus.isDefined, flat.focus.isDefined) match {
      case (true, true) =>
        Left(
          DecodingFailure(
            message = AclCreationErrorCoders.stringify(
              auditSettingsError(
                "Duplicated audit 'default_acl_log_enabled' setting: use either the nested form 'audit: {default_acl_log_enabled: ...}' or the flat form 'audit.default_acl_log_enabled', not both"
              )
            ),
            ops = Nil
          )
        )
      case (true, false) => nested.as[Option[Boolean]].map(_.getOrElse(true))
      case (false, _)    => flat.as[Option[Boolean]].map(_.getOrElse(true))
    }
  }

  private def auditSettingsDecoder[M <: Mode](
      decoder: Decoder[AuditOutputsConfig[M]]
  ): Decoder[Option[AuditOutputsConfig[M]]] =
    Decoder.instance(c =>
      readAuditEnabled(c).flatMap {
        case Some(true) => decoder(c).map(Some.apply)
        case _          => Right(None)
      }
    )

  private def readAuditEnabled(c: HCursor): Decoder.Result[Option[Boolean]] = {
    val nested = c.downField("audit").downField("enabled")
    val flat = c.downField("audit.enabled")
    (nested.focus.isDefined, flat.focus.isDefined) match {
      case (true, true) =>
        Left(
          DecodingFailure(
            message = AclCreationErrorCoders.stringify(
              auditSettingsError(
                "Duplicated audit 'enabled' setting: use either the nested form 'audit: {enabled: ...}' or the flat form 'audit.enabled', not both"
              )
            ),
            ops = Nil
          )
        )
      case (true, false) => nested.as[Option[Boolean]]
      case (false, _)    => flat.as[Option[Boolean]]
    }
  }

  private def decodeStandardAuditSettings: Decoder[AuditOutputsConfig[Mode.Standard]] = {
    decodeAuditSettingsWithFallback[Mode.Standard](
      simpleDecoder = auditOutputSimpleDecoder[AuditOutputType.Standard, Mode.Standard] {
        case (AuditOutputType.DataStream, name) => EsDataStreamBased(name, EsDataStreamBasedSink.default)
        case (AuditOutputType.Index, name)      => EsIndexBased(name, EsIndexBasedSink.default)
        case (AuditOutputType.Log, name)        => LogBased(name, LogBasedSink.default)
      },
      extendedDecoder = auditOutputExtendedDecoder[AuditOutputType.Standard, Mode.Standard] {
        case (c, AuditOutputType.DataStream, name) =>
          c.as[EsDataStreamBasedSink].map(cfg => EsDataStreamBased(name, cfg))
        case (c, AuditOutputType.Index, name) => c.as[EsIndexBasedSink].map(cfg => EsIndexBased(name, cfg))
        case (c, AuditOutputType.Log, name)   => decodeLogOutput(name)(c)
      }
    )
  }

  private def decodeLegacyAuditSettings: Decoder[AuditOutputsConfig[Mode.Legacy]] = {
    decodeAuditSettingsWithFallback[Mode.Legacy](
      simpleDecoder = auditOutputSimpleDecoder[AuditOutputType.Legacy, Mode.Legacy] {
        case (AuditOutputType.Index, name) => EsIndexBased(name, EsIndexBasedSink.default)
        case (AuditOutputType.Log, name)   => LogBased(name, LogBasedSink.default)
      },
      extendedDecoder = auditOutputExtendedDecoder[AuditOutputType.Legacy, Mode.Legacy] {
        case (c, AuditOutputType.Index, name) => c.as[EsIndexBasedSink].map(cfg => EsIndexBased(name, cfg))
        case (c, AuditOutputType.Log, name)   => decodeLogOutput(name)(c)
      }
    )
  }

  private def decodeLogOutput(name: SinkName): Decoder[LogBased | RollingFileBased] = {
    logBasedSinkConfigDecoder.map {
      case cfg: LogBasedSink         => LogBased(name, cfg)
      case cfg: RollingFileBasedSink => RollingFileBased(name, cfg)
    }
  }

  private def decodeAuditSettingsWithFallback[M <: Mode](
      simpleDecoder: Decoder[AuditOutput[M]],
      extendedDecoder: Decoder[AuditOutput[M]]
  ): Decoder[AuditOutputsConfig[M]] = {
    decodeAuditSettingsWith(
      using simpleDecoder
    )
      .handleErrorWith { error =>
        if (error.aclCreationError.isDefined) {
          // the schema was valid, but the config not
          Decoder.failed(error)
        } else {
          decodeAuditSettingsWith(
            using extendedDecoder
          )
        }
      }
  }

  private def decodeAuditSettingsWith[M <: Mode](
      using Decoder[AuditOutput[M]]
  ): Decoder[AuditOutputsConfig[M]] =
    SyncDecoderCreator
      .instance {
        _.downField("audit").downField("outputs").as[Option[List[AuditOutput[M]]]]
      }
      .emapE {
        case Some(outputs) =>
          NonEmptyList
            .fromList[AuditOutput[M]](outputs.distinct)
            .map(AuditOutputsConfig.WithOutputs(_))
            .toRight(auditSettingsError(s"The audit 'outputs' array cannot be empty"))
        case None =>
          AuditOutputsConfig.NoOutputsConfigured.asRight
      }
      .decoder

  private def auditOutputSimpleDecoder[OUTPUT_TYPE <: AuditOutputType, M <: Mode](
      f: (OUTPUT_TYPE, SinkName) => AuditOutput[M]
  )(
      using Decoder[OUTPUT_TYPE]
  ): Decoder[AuditOutput[M]] =
    Decoder[OUTPUT_TYPE].map(st => f(st, SinkName.random()))

  private def auditOutputExtendedDecoder[OUTPUT_TYPE <: AuditOutputType, M <: Mode](
      f: (HCursor, OUTPUT_TYPE, SinkName) => Decoder.Result[AuditOutput[M]]
  )(
      using Decoder[OUTPUT_TYPE]
  ): Decoder[AuditOutput[M]] =
    Decoder.instance { c =>
      for {
        outputType <- c.downFieldAs[OUTPUT_TYPE]("type")
        isOutputEnabledOpt <- c.downFieldAs[Option[Boolean]]("enabled")
        sinkNameOpt <- c.downFieldAs[Option[SinkName]]("name")
        name = sinkNameOpt.getOrElse(SinkName.random())
        result <- f(c, outputType, name)
      } yield {
        if (isOutputEnabledOpt.getOrElse(true)) result else AuditOutput.Disabled
      }
    }

  private sealed trait AuditOutputType

  private object AuditOutputType {
    sealed trait Standard extends AuditOutputType

    sealed trait Legacy extends AuditOutputType

    case object Index extends Standard with Legacy

    case object Log extends Standard with Legacy

    case object DataStream extends Standard

    given Decoder[Legacy] =
      SyncDecoderCreator
        .from(Decoder.decodeString)
        .emapE[Legacy] {
          case "index"       => Right(Index)
          case "log"         => Right(Log)
          case "data_stream" =>
            Left(
              auditSettingsError(
                s"Data stream audit output is supported from Elasticsearch version ${EsFeatureVersions.dataStreamSupport.formatted}. " +
                  s"Use 'index' type or upgrade to ${EsFeatureVersions.dataStreamSupport.formatted} or later."
              )
            )
          case other =>
            Left(unsupportedOutputTypeError(unsupportedType = other, supportedTypes = NonEmptyList.of("index", "log")))
        }
        .decoder

    given Decoder[Standard] =
      SyncDecoderCreator
        .from(Decoder.decodeString)
        .emapE[Standard] {
          case "index"       => Right(Index)
          case "log"         => Right(Log)
          case "data_stream" => Right(DataStream)
          case other         =>
            Left(
              unsupportedOutputTypeError(
                unsupportedType = other,
                supportedTypes = NonEmptyList.of("data_stream", "index", "log")
              )
            )
        }
        .decoder

    private def unsupportedOutputTypeError(unsupportedType: String, supportedTypes: NonEmptyList[String]) = {
      auditSettingsError(
        s"Unsupported type of audit output: ${unsupportedType.show}. Supported types: [${supportedTypes.toList.show}]"
      )
    }

  }

  given Decoder[RorAuditLoggerName] = {
    SyncDecoderCreator
      .from(nonEmptyStringDecoder)
      .map(RorAuditLoggerName.apply)
      .withError(auditSettingsError("The audit 'logger_name' cannot be empty"))
      .decoder
  }

  given Decoder[RollingFileBasedSink.FileAppenderConfig] = Decoder.instance { c =>
    for {
      filePath <- c.downField("file_path").as[String].map(java.nio.file.Paths.get(_))
      maxFileSize <- c.downField("max_file_size").as[String].flatMap { raw =>
        FromString.information
          .decode(raw)
          .filterOrElse(_.toBytes > 0.0, s"Size must be greater than zero")
          .left
          .map { msg =>
            DecodingFailure(
              AclCreationErrorCoders.stringify(
                auditSettingsError(s"Invalid audit 'max_file_size': $msg")
              ),
              Nil
            )
          }
      }
      maxFiles <- c.downField("max_files").as[Int].flatMap { n =>
        refineV[Positive](n).leftMap(_ =>
          DecodingFailure(
            AclCreationErrorCoders.stringify(
              auditSettingsError(s"Audit 'max_files' must be a positive integer, got: $n")
            ),
            Nil
          )
        )
      }
    } yield RollingFileBasedSink.FileAppenderConfig(filePath, maxFileSize, maxFiles)
  }

  private given logBasedSinkConfigDecoder: Decoder[LogBasedSink | RollingFileBasedSink] = {
    given logSinkSerializerDecoder: Decoder[Option[AuditSerializer]] = Decoder.instance { c =>
      c.as[SerializerType].flatMap {
        case SerializerType.AclSerializer => Right(Some(AuditSerializer.Acl))
        case st                           => decodeNonAclSerializer(st, c)
      }
    }

    Decoder.instance { c =>
      for {
        logSerializer <- c.as[Option[AuditSerializer]]
        loggerName <- c.downField("logger_name").as[Option[RorAuditLoggerName]]
        fileAppender <- c.downField("file_appender").as[Option[RollingFileBasedSink.FileAppenderConfig]]
      } yield {
        val serializer = logSerializer.getOrElse(LogBasedSink.default.serializer)
        val logger = loggerName.getOrElse(LogBasedSink.default.loggerName)
        fileAppender match {
          case None     => LogBasedSink(serializer, logger)
          case Some(fa) => RollingFileBasedSink(serializer, logger, fa)
        }
      }
    }
  }

  private given Decoder[EsIndexBasedSink] = Decoder.instance { c =>
    for {
      auditIndexTemplate <- c.downField("index_template").as[Option[RorAuditIndexTemplate]]
      logSerializer <- c.as[Option[JsonAuditSerializer]]
      remoteAuditCluster <- c.downField("cluster").as[Option[AuditCluster.RemoteAuditCluster]]
    } yield EsIndexBasedSink(
      logSerializer.getOrElse(EsIndexBasedSink.default.serializer),
      auditIndexTemplate.getOrElse(EsIndexBasedSink.default.rorAuditIndexTemplate),
      remoteAuditCluster.getOrElse(EsIndexBasedSink.default.auditCluster),
    )
  }

  private given Decoder[EsDataStreamBasedSink] = Decoder.instance { c =>
    for {
      rorAuditDataStream <- c.downFieldAs[Option[RorAuditDataStream]]("data_stream")
      logSerializer <- c.as[Option[JsonAuditSerializer]]
      remoteAuditCluster <- c.downFieldAs[Option[AuditCluster.RemoteAuditCluster]]("cluster")
    } yield EsDataStreamBasedSink(
      logSerializer.getOrElse(EsDataStreamBasedSink.default.serializer),
      rorAuditDataStream.getOrElse(EsDataStreamBasedSink.default.rorAuditDataStream),
      remoteAuditCluster.getOrElse(EsDataStreamBasedSink.default.auditCluster),
    )
  }

  private given Decoder[SinkName] = Decoder.decodeString.map(SinkName.apply)

  private given Decoder[RorAuditDataStream] =
    SyncDecoderCreator
      .from(common.nonEmptyStringDecoder)
      .emapE { patternStr =>
        RorAuditDataStream(patternStr)
          .leftMap { case RorAuditDataStream.CreationError.FormatError(msg) =>
            auditSettingsError(s"Illegal format for ROR audit 'data_stream' name - ${msg.show}")
          }
      }
      .decoder

  private given Decoder[RorAuditIndexTemplate] =
    SyncDecoderCreator
      .from(nonEmptyStringDecoder)
      .emapE { patternNes =>
        RorAuditIndexTemplate.from(patternNes).left.map { case CreationError.ParsingError(msg) =>
          auditSettingsError(
            s"Illegal pattern specified for audit index template. Have you misplaced quotes? See https://docs.oracle.com/javase/8/docs/api/java/time/format/DateTimeFormatter.html to learn the syntax. Pattern was: ${patternNes.show} error: ${msg.show}"
          )
        }
      }
      .decoder

  private def decodeNonAclSerializer(
      serializerType: SerializerType,
      c: HCursor
  ): Decoder.Result[Option[JsonAuditSerializer]] =
    serializerType match {
      case SerializerType.SimpleSyntaxStaticSerializer =>
        c.as[Option[AuditLogSerializer]](simpleSyntaxSerializerDecoder)
          .map(_.map(AuditSerializer.Delegating.apply))
      case SerializerType.ExtendedSyntaxStaticSerializer =>
        c.downField("serializer")
          .as[Option[AuditLogSerializer]](extendedSyntaxStaticSerializerDecoder)
          .map(_.map(AuditSerializer.Delegating.apply))
      case SerializerType.ExtendedSyntaxConfigurableSerializer =>
        c.downField("serializer").as[Option[JsonAuditSerializer]](extendedSyntaxConfigurableSerializerDecoder)
      case SerializerType.EcsSerializer =>
        c.downField("serializer").as[Option[JsonAuditSerializer]](ecsSerializerDecoder)
      case SerializerType.AclSerializer =>
        Left(
          DecodingFailure(
            AclCreationErrorCoders.stringify(
              auditSettingsError("ACL serializer can only be used with log-based sinks (type: log)")
            ),
            Nil
          )
        )
    }

  private def ecsSerializerDecoder: Decoder[Option[JsonAuditSerializer]] = Decoder.instance { c =>
    for {
      version <- c
        .downField("version")
        .as[Option[EcsSerializerVersion]]
        .left
        .map(withAuditingSettingsCreationErrorMessage(msg => s"ECS serializer 'version' is invalid: $msg"))
      allowedEventMode <- decodeAllowedEventMode(c).left
        .map(
          withAuditingSettingsCreationErrorMessage(msg =>
            s"ECS serializer is used, but the 'allowed_events_serialization_mode' setting is invalid: $msg"
          )
        )
      includeFullRequestContentOpt <- c.downField("include_full_request_content").as[Option[Boolean]]
      includeFullRequestContent = includeFullRequestContentOpt.getOrElse(false)
      serializer = version match {
        case None =>
          AuditSerializer.EcsV1(allowedEventMode, includeFullRequestContent)
        case Some(EcsSerializerVersion.V1) =>
          AuditSerializer.EcsV1(allowedEventMode, includeFullRequestContent)
      }
    } yield Some(serializer)
  }

  private def extendedSyntaxConfigurableSerializerDecoder: Decoder[Option[JsonAuditSerializer]] = Decoder.instance {
    c =>
      for {
        allowedEventMode <- decodeAllowedEventMode(c).left
          .map(
            withAuditingSettingsCreationErrorMessage(msg =>
              s"Configurable serializer is used, but the 'allowed_events_serialization_mode' setting is invalid: $msg"
            )
          )
        fields <- c
          .downField("fields")
          .as[Map[AuditFieldPath, AuditFieldValueDescriptor]]
          .left
          .map(
            withAuditingSettingsCreationErrorMessage(msg =>
              s"Configurable serializer is used, but the 'fields' setting is missing or invalid: $msg"
            )
          )
        serializer = AuditSerializer.Configurable(allowedEventMode, fields)
      } yield Some(serializer)
  }

  private def decodeAllowedEventMode(c: HCursor): Decoder.Result[AllowedEventMode] = {
    c.downField("allowed_events_serialization_mode").focus match {
      case Some(_) =>
        c.downField("allowed_events_serialization_mode").as[AllowedEventMode](allowedEventsSerializationModeDecoder)
      case None =>
        c.downField("verbosity_level_serialization_mode").as[AllowedEventMode]
    }
  }

  private val allowedEventsSerializationModeDecoder: Decoder[AllowedEventMode] =
    Decoder.instance { c =>
      if (c.value.isArray)
        Left(
          DecodingFailure(
            "expected a string value (\"always\" or \"based_on_block_settings\"); " +
              "if migrating from 'verbosity_level_serialization_mode', note that the value format changed from an array like [\"INFO\"] to a plain string",
            c.history
          )
        )
      else
        Decoder.decodeString
          .emap {
            case "always"                  => Right(AllowedEventMode.IncludeAll)
            case "based_on_block_settings" => Right(AllowedEventMode.Include(Set(Verbosity.Info)))
            case other => Left(s"Unknown value '$other', allowed values are: [always, based_on_block_settings]")
          }
          .apply(c)
    }

  private def extendedSyntaxStaticSerializerDecoder: Decoder[Option[AuditLogSerializer]] = Decoder.instance { c =>
    for {
      fullClassNameOpt <- c.downField("class_name").as[Option[String]]
      serializerOpt <- fullClassNameOpt match {
        case Some(fullClassName) => serializerByClassName(fullClassName)
        case None                => Right(None)
      }
    } yield serializerOpt
  }

  private def simpleSyntaxSerializerDecoder: Decoder[Option[AuditLogSerializer]] = Decoder.instance { c =>
    for {
      fullClassNameOpt <- c.downField("serializer").as[Option[String]]
      legacyFullClassNameOpt <- c.downField("audit_serializer").as[Option[String]]
      serializerOpt <- fullClassNameOpt.orElse(legacyFullClassNameOpt) match {
        case Some(fullClassName) => serializerByClassName(fullClassName)
        case None                => Right(None)
      }
    } yield serializerOpt
  }

  private def serializerByClassName(className: String): Either[DecodingFailure, Some[AuditLogSerializer]] = {
    createSerializerInstanceFromClassName(className)
      .map(Some(_))
      .left
      .map(error => DecodingFailure(AclCreationErrorCoders.stringify(error), Nil))
  }

  private given serializerTypeDecoder: Decoder[SerializerType] = Decoder.instance { c =>
    c.downField("serializer").as[Option[Json]].flatMap {
      case Some(json) if json.isObject =>
        json.hcursor.downField("type").as[String].map(_.toLowerCase).flatMap {
          case "static" =>
            Right(SerializerType.ExtendedSyntaxStaticSerializer)
          case "configurable" =>
            Right(SerializerType.ExtendedSyntaxConfigurableSerializer)
          case "ecs" =>
            Right(SerializerType.EcsSerializer)
          case "acl" =>
            Right(SerializerType.AclSerializer)
          case other =>
            Left(
              DecodingFailure(
                AclCreationErrorCoders.stringify(
                  auditSettingsError(
                    s"Invalid serializer type '$other', allowed values [static, configurable, ecs, acl]"
                  )
                ),
                Nil
              )
            )
        }
      case Some(_) | None =>
        Right(SerializerType.SimpleSyntaxStaticSerializer)
    }
  }

  private given jsonAuditSerializerDecoder: Decoder[Option[JsonAuditSerializer]] = Decoder.instance { c =>
    c.as[SerializerType].flatMap(decodeNonAclSerializer(_, c))
  }

  private sealed trait SerializerType

  private object SerializerType {
    case object SimpleSyntaxStaticSerializer extends SerializerType

    case object ExtendedSyntaxStaticSerializer extends SerializerType

    case object ExtendedSyntaxConfigurableSerializer extends SerializerType

    case object EcsSerializer extends SerializerType

    case object AclSerializer extends SerializerType
  }

  private given ecsSerializerVersionDecoder: Decoder[EcsSerializerVersion] =
    Decoder.decodeString.map(_.toLowerCase).emap {
      case "v1"  => Right(EcsSerializerVersion.V1)
      case other => Left(s"Invalid ECS serializer version $other")
    }

  private sealed trait EcsSerializerVersion

  private object EcsSerializerVersion {
    case object V1 extends EcsSerializerVersion
  }

  private def withAuditingSettingsCreationErrorMessage(message: String => String)(decodingFailure: DecodingFailure) = {
    decodingFailure.withMessage(AclCreationErrorCoders.stringify(auditSettingsError(message(decodingFailure.message))))
  }

  @nowarn("cat=deprecation")
  private def createSerializerInstanceFromClassName(
      fullClassName: String
  ): Either[AuditingSettingsCreationError, AuditLogSerializer] = {
    val clazz = Try(Class.forName(fullClassName)).fold(
      {
        case _: ClassNotFoundException =>
          throw new IllegalStateException(s"Serializer with class name $fullClassName not found.")
        case other => throw other
      },
      identity
    )

    def createInstanceOfSimpleSerializer(): Try[Any] =
      Try(clazz.getDeclaredConstructor()).map(_.newInstance())

    val serializer = createInstanceOfSimpleSerializer().getOrElse(
      throw new IllegalStateException(
        s"Class ${clazz.getName} is required to have either one (AuditEnvironmentContext) parameter constructor or constructor without parameters"
      )
    )

    Try {
      serializer match {
        case serializer: tech.beshu.ror.audit.AuditLogSerializer =>
          Some((serializer, serializer.getClass.getName))
        case serializer: tech.beshu.ror.audit.EnvironmentAwareAuditLogSerializer =>
          Some((new EnvironmentAwareAuditLogSerializerAdapter(serializer), serializer.getClass.getName))
        case serializer: tech.beshu.ror.requestcontext.AuditLogSerializer[_]
            if fullClassName.startsWith("tech.beshu.ror.requestcontext") =>
          Some((new DeprecatedAuditLogSerializerAdapter(serializer), serializer.getClass.getName))
        case serializer: tech.beshu.ror.requestcontext.AuditLogSerializer[_] =>
          Some((new DeprecatedAuditLogSerializerAdapter(serializer), serializer.getClass.getName))
        case _ => None
      }
    } match {
      case Success(Some((customSerializer, name))) =>
        noRequestIdLogger.info(s"Using custom serializer: $name")
        Right(customSerializer)
      case Success(None) =>
        Left(
          auditSettingsError(
            s"Class ${fullClassName.show} is not a subclass of ${classOf[AuditLogSerializer].getName.show} or ${classOf[tech.beshu.ror.requestcontext.AuditLogSerializer[_]].getName.show}"
          )
        )
      case Failure(ex) =>
        Left(
          auditSettingsError(s"Cannot create instance of class '${fullClassName.show}', error: ${ex.getMessage.show}")
        )
    }
  }

  private given allowedEventModeDecoder: Decoder[AllowedEventMode] = {
    SyncDecoderCreator
      .from(Decoder[Option[Set[Verbosity]]])
      .map[AllowedEventMode] {
        case Some(verbosityLevels) => AllowedEventMode.Include(verbosityLevels)
        case None                  => AllowedEventMode.IncludeAll
      }
      .decoder
  }

  private given auditFieldsDecoder: Decoder[Map[AuditFieldPath, AuditFieldValueDescriptor]] =
    Decoder.instance { cursor =>
      def decodeJson(json: Json, path: List[String]): Decoder.Result[Map[List[String], AuditFieldValueDescriptor]] = {
        json.fold(
          jsonNull = Left(DecodingFailure("Expected AuditFieldValueDescriptor, got null", cursor.history)),
          jsonBoolean = b => Right(Map(path -> AuditFieldValueDescriptor.BooleanValue(b))),
          jsonNumber = n =>
            n.toBigDecimal
              .map(bd => Map(path -> AuditFieldValueDescriptor.NumericValue(bd)))
              .toRight(DecodingFailure("Cannot decode number", cursor.history)),
          jsonString = s =>
            AuditFieldValueDescriptorParser
              .parse(s)
              .map(desc => Map(path -> desc))
              .left
              .map(err => DecodingFailure(err, cursor.history)),
          jsonArray = _ => Left(DecodingFailure("AuditFieldValueDescriptor cannot be an array", cursor.history)),
          jsonObject = obj =>
            obj.toList
              .traverse { case (k, v) => decodeJson(v, path :+ k) }
              .map(_.foldLeft(Map.empty[List[String], AuditFieldValueDescriptor])(_ ++ _))
        )
      }

      for {
        rawResult <- decodeJson(cursor.value, Nil)
        result = rawResult.view.map { case (path, value) =>
          NonEmptyList.fromList(path) match {
            case Some(nonEmptyPath) =>
              AuditFieldPath(nonEmptyPath.head, nonEmptyPath.tail) -> value
            case None =>
              throw new IllegalStateException(
                s"Empty audit field path encountered when decoding configurable audit fields definition."
              )
          }
        }.toMap
      } yield result
    }

  private given verbosityDecoder: Decoder[Verbosity] = {
    SyncDecoderCreator
      .from(Decoder.decodeString)
      .emap {
        case "ERROR" => Right(Verbosity.Error: Verbosity)
        case "INFO"  => Right(Verbosity.Info: Verbosity)
        case other   => Left(s"Unknown verbosity level [$other], allowed values are: [ERROR, INFO]")
      }
      .decoder
  }

  private def auditSettingsError(message: String) = AuditingSettingsCreationError(Message(message))

  private given Decoder[AuditCluster.RemoteAuditCluster] = {
    given Decoder[UniqueNonEmptyList[AuditClusterNode]] = {
      SyncDecoderCreator
        .from(Decoder.decodeNonEmptyList[Uri])
        .withError(auditSettingsError("Non empty list of valid URI is required"))
        .map(_.map(AuditClusterNode.apply))
        .map(UniqueNonEmptyList.fromNonEmptyList)
        .decoder
    }

    given Decoder[ClusterMode] =
      SyncDecoderCreator
        .from(Decoder.decodeString)
        .emapE[ClusterMode] {
          case "round-robin" => Right(ClusterMode.RoundRobin)
          case "failover"    => Right(ClusterMode.Failover)
          case other         =>
            Left(auditSettingsError(s"Unknown cluster mode [$other], allowed values are: [round-robin,failover]"))
        }
        .decoder

    def clusterCredentialsFromNodesUris(
        nodes: UniqueNonEmptyList[AuditClusterNode]
    ): Either[AuditingSettingsCreationError, Option[NodeCredentials]] = {
      val (
        nodesWithoutCredentials: Iterable[AuditClusterNode],
        nodesWithCredentials: Iterable[(AuditClusterNode, NodeCredentials)]
      ) =
        nodes.partitionMap(n => n.credentials.map(c => (n, c)).toRight(n))

      if (nodesWithoutCredentials.size == nodes.size) {
        Right(None)
      } else {
        // check if all nodes have the same credentials - if not, it's possible that different clusters are used
        lazy val inconsistentCredentialsError =
          auditSettingsError(
            s"One or more audit cluster nodes have inconsistent credentials. Please configure the same credentials. Nodes: ${nodes.map(_.uri.show).toList.show}"
          )
        for {
          _ <- Either.cond(nodesWithoutCredentials.isEmpty, (), inconsistentCredentialsError)
          credentials <- {
            nodesWithCredentials match {
              case (_, firstNodeCredentials) :: Nil =>
                Right(Some(firstNodeCredentials))
              case (_, firstNodeCredentials) :: otherNodes =>
                val nodesWithOtherCredentials = otherNodes.collect {
                  case (node, credentials) if credentials != firstNodeCredentials => node
                }
                Either.cond(
                  nodesWithOtherCredentials.isEmpty,
                  Some(firstNodeCredentials),
                  inconsistentCredentialsError
                )
            }
          }
        } yield credentials
      }
    }

    Decoder.instance[RemoteAuditCluster] {
      case c if c.values.isDefined =>
        // array syntax - use remote cluster with default mode - round-robin
        for {
          clusterNodes <- c.as[UniqueNonEmptyList[AuditClusterNode]]
          maybeCredentials <- clusterCredentialsFromNodesUris(clusterNodes)
            .leftMap(error => DecodingFailure(AclCreationErrorCoders.stringify(error), Nil))
        } yield AuditCluster.RemoteAuditCluster(
          nodes = clusterNodes,
          mode = ClusterMode.RoundRobin,
          credentials = maybeCredentials,
          ignoreClusterConnectivityProblems = false
        )
      case c =>
        // extended syntax
        val usernameKey = "username"
        val passwordKey = "password"
        for {
          clusterNodes <- c.downFieldAs[UniqueNonEmptyList[AuditClusterNode]]("nodes")
          mode <- c.downFieldAs[ClusterMode]("mode")
          username <- c.downFieldAs[Option[NonEmptyString]](usernameKey)
          password <- c.downFieldAs[Option[NonEmptyString]](passwordKey)
          maybeCredentials <- {
            (username, password) match {
              case (Some(user), Some(pass)) =>
                Right(Some(NodeCredentials(user, pass)))
              case (None, None) =>
                clusterCredentialsFromNodesUris(clusterNodes)
              case (Some(user), None) =>
                Left(auditSettingsError(s"Audit output configuration is missing the '$passwordKey' field."))
              case (None, Some(pass)) =>
                Left(auditSettingsError(s"Audit output configuration is missing the '$usernameKey' field."))
            }
          }.leftMap(error => DecodingFailure(AclCreationErrorCoders.stringify(error), Nil))
          maybeIgnoreProblems <- c.downFieldAs[Option[Boolean]]("ignore_es_connectivity_problems")
        } yield AuditCluster.RemoteAuditCluster(
          clusterNodes,
          mode,
          maybeCredentials,
          maybeIgnoreProblems.getOrElse(false)
        )
    }
  }

  private object DeprecatedAuditSettingsDecoder {

    def instance: Decoder[Option[AuditOutputsConfig[Mode.Both]]] = Decoder.instance { c =>
      whenEnabled(c) {
        for {
          auditIndexTemplate <- decodeOptionalSetting[RorAuditIndexTemplate](c)(
            "index_template",
            fallbackKey = "audit_index_template"
          )
          logSerializerOutsideAuditSection <- c.as[Option[JsonAuditSerializer]](jsonAuditSerializerDecoder)
          logSerializerInAuditSection <- c
            .downField("audit")
            .success
            .map(_.as[Option[JsonAuditSerializer]])
            .getOrElse(Right(None))
          logSerializer = logSerializerOutsideAuditSection.orElse(logSerializerInAuditSection)
          remoteAuditCluster <- decodeOptionalSetting[AuditCluster.RemoteAuditCluster](c)(
            "cluster",
            fallbackKey = "audit_cluster"
          )
        } yield AuditOutputsConfig.WithOutputs(
          outputs = NonEmptyList.one(
            EsIndexBased(
              SinkName.random(),
              EsIndexBasedSink(
                serializer = logSerializer.getOrElse(EsIndexBasedSink.default.serializer),
                rorAuditIndexTemplate = auditIndexTemplate.getOrElse(EsIndexBasedSink.default.rorAuditIndexTemplate),
                auditCluster = remoteAuditCluster.getOrElse(EsIndexBasedSink.default.auditCluster),
              )
            )
          )
        )
      }
    }

    private def whenEnabled[M <: Mode](cursor: HCursor)(decoding: => Decoder.Result[AuditOutputsConfig[M]]) = {
      for {
        isEnabled <- decodeOptionalSetting[Boolean](cursor)("collector", fallbackKey = "audit_collector")
        result <- if (isEnabled.getOrElse(false)) decoding.map(Some.apply) else Right(None)
      } yield result
    }

    private def decodeOptionalSetting[T: Decoder](
        cursor: HCursor
    )(key: String, fallbackKey: String): Decoder.Result[Option[T]] = {
      cursor
        .downField("audit")
        .downFieldAs[Option[T]](key)
        .flatMap {
          case Some(value) => Right(Some(value))
          case None        => cursor.downFieldAs[Option[T]](fallbackKey)
        }
    }

  }

}
