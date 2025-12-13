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
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.*
import io.circe.Decoder.*
import io.lemonlabs.uri.Uri
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.audit.AuditingTool
import tech.beshu.ror.accesscontrol.audit.AuditingTool.AuditSettings.AuditSink
import tech.beshu.ror.accesscontrol.audit.AuditingTool.AuditSettings.AuditSink.Config
import tech.beshu.ror.accesscontrol.audit.AuditingTool.AuditSettings.AuditSink.Config.{EsDataStreamBasedSink, EsIndexBasedSink, LogBasedSink}
import tech.beshu.ror.accesscontrol.audit.configurable.{AuditFieldValueDescriptorParser, ConfigurableAuditLogSerializer}
import tech.beshu.ror.accesscontrol.audit.ecs.EcsV1AuditLogSerializer
import tech.beshu.ror.accesscontrol.domain.AuditCluster.{AuditClusterNode, ClusterMode, NodeCredentials, RemoteAuditCluster}
import tech.beshu.ror.accesscontrol.domain.RorAuditIndexTemplate.CreationError
import tech.beshu.ror.accesscontrol.domain.{AuditCluster, RorAuditDataStream, RorAuditIndexTemplate, RorAuditLoggerName}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.{AuditingSettingsCreationError, Reason}
import tech.beshu.ror.accesscontrol.factory.decoders.common.{lemonLabsUriDecoder, nonEmptyStringDecoder}
import tech.beshu.ror.accesscontrol.utils.CirceOps.*
import tech.beshu.ror.accesscontrol.utils.SyncDecoderCreator
import tech.beshu.ror.audit.AuditLogSerializer
import tech.beshu.ror.audit.AuditResponseContext.Verbosity
import tech.beshu.ror.audit.adapters.*
import tech.beshu.ror.audit.utils.AuditSerializationHelper.{AllowedEventMode, AuditFieldPath, AuditFieldValueDescriptor}
import tech.beshu.ror.es.EsVersion
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList
import tech.beshu.ror.utils.yaml.YamlKeyDecoder

import scala.annotation.nowarn
import scala.util.{Failure, Success, Try}

object AuditingSettingsDecoder extends Logging {

  def instance(esVersion: EsVersion): Decoder[Option[AuditingTool.AuditSettings]] = {
    for {
      auditSettings <- auditSettingsDecoder(esVersion)
      deprecatedAuditSettings <- DeprecatedAuditSettingsDecoder.instance
    } yield auditSettings.orElse(deprecatedAuditSettings)
  }

  private def auditSettingsDecoder(esVersion: EsVersion): Decoder[Option[AuditingTool.AuditSettings]] = Decoder.instance { c =>
    for {
      isAuditEnabled <- YamlKeyDecoder[Boolean](
        segments = NonEmptyList.of("audit", "enabled"),
        default = false
      ).apply(c)
      result <- if (isAuditEnabled) {
        decodeAuditSettings(using esVersion)(c).map(Some.apply)
      } else {
        Right(None)
      }
    } yield result
  }

  private def decodeAuditSettings(using EsVersion) = {
    decodeAuditSettingsWith(using auditSinkConfigSimpleDecoder)
      .handleErrorWith { error =>
        if (error.aclCreationError.isDefined) {
          // the schema was valid, but the config not
          Decoder.failed(error)
        } else {
          decodeAuditSettingsWith(using auditSinkConfigExtendedDecoder)
        }
      }
  }

  private def decodeAuditSettingsWith(using Decoder[AuditSink]) = {
    SyncDecoderCreator
      .instance {
        _.downField("audit").downField("outputs").as[Option[List[AuditSink]]]
      }
      .emapE {
        case Some(outputs) =>
          NonEmptyList
            .fromList(outputs.distinct)
            .map(AuditingTool.AuditSettings.apply)
            .toRight(auditSettingsError(s"The audit 'outputs' array cannot be empty"))
        case None =>
          AuditingTool.AuditSettings(
            NonEmptyList.of(AuditSink.Enabled(EsIndexBasedSink.default))
          ).asRight
      }
      .decoder
  }

  private def auditSinkConfigSimpleDecoder(using EsVersion): Decoder[AuditSink] = {
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

  private def auditSinkConfigExtendedDecoder(using EsVersion): Decoder[AuditSink] = {
    given Decoder[RorAuditLoggerName] = {
      SyncDecoderCreator
        .from(nonEmptyStringDecoder)
        .map(RorAuditLoggerName.apply)
        .withError(auditSettingsError("The audit 'logger_name' cannot be empty"))
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
        rorAuditDataStream <- c.downFieldAs[Option[RorAuditDataStream]]("data_stream")
        logSerializer <- c.as[Option[AuditLogSerializer]]
        remoteAuditCluster <- c.downFieldAs[Option[AuditCluster.RemoteAuditCluster]]("cluster")
      } yield EsDataStreamBasedSink(
        logSerializer.getOrElse(EsDataStreamBasedSink.default.logSerializer),
        rorAuditDataStream.getOrElse(EsDataStreamBasedSink.default.rorAuditDataStream),
        remoteAuditCluster.getOrElse(EsDataStreamBasedSink.default.auditCluster),
      )
    }

    Decoder
      .instance[AuditSink] { c =>
        for {
          sinkType <- c.downFieldAs[AuditSinkType]("type")
          sinkConfig <- sinkType match {
            case AuditSinkType.DataStream => c.as[EsDataStreamBasedSink]
            case AuditSinkType.Index => c.as[EsIndexBasedSink]
            case AuditSinkType.Log => c.as[LogBasedSink]
          }
          isSinkEnabled <- c.downFieldAs[Option[Boolean]]("enabled")
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
              auditSettingsError(s"Illegal format for ROR audit 'data_stream' name - ${msg.show}")
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
              auditSettingsError(
                s"Illegal pattern specified for audit index template. Have you misplaced quotes? Search for 'DateTimeFormatter patterns' to learn the syntax. Pattern was: ${patternStr.show} error: ${msg.show}"
              )
          }
      }
      .decoder

  given auditLogSerializerDecoder: Decoder[Option[AuditLogSerializer]] = Decoder.instance { c =>
    for {
      serializerTypeStr <- c.as[SerializerType]
      result <- serializerTypeStr match {
        case SerializerType.SimpleSyntaxStaticSerializer =>
          c.as[Option[AuditLogSerializer]](simpleSyntaxSerializerDecoder)
        case SerializerType.ExtendedSyntaxStaticSerializer =>
          c.downField("serializer").as[Option[AuditLogSerializer]](extendedSyntaxStaticSerializerDecoder)
        case SerializerType.ExtendedSyntaxConfigurableSerializer =>
          c.downField("serializer").as[Option[AuditLogSerializer]](extendedSyntaxConfigurableSerializerDecoder)
        case SerializerType.EcsSerializer =>
          c.downField("serializer").as[Option[AuditLogSerializer]](ecsSerializerDecoder)
      }
    } yield result
  }

  private def ecsSerializerDecoder: Decoder[Option[AuditLogSerializer]] = Decoder.instance { c =>
    for {
      version <- c.downField("version").as[Option[EcsSerializerVersion]]
        .left.map(withAuditingSettingsCreationErrorMessage(msg => s"ECS serializer 'version' is invalid: $msg"))
      allowedEventMode <- c.downField("verbosity_level_serialization_mode").as[AllowedEventMode]
        .left.map(withAuditingSettingsCreationErrorMessage(msg => s"ECS serializer is used, but the 'verbosity_level_serialization_mode' setting is invalid: $msg"))
      includeFullRequestContentOpt <- c.downField("include_full_request_content").as[Option[Boolean]]
      includeFullRequestContent = includeFullRequestContentOpt.getOrElse(false)
      serializer = version match {
        case None =>
          new EcsV1AuditLogSerializer(allowedEventMode, includeFullRequestContent)
        case Some(EcsSerializerVersion.V1) =>
          new EcsV1AuditLogSerializer(allowedEventMode, includeFullRequestContent)
      }
    } yield Some(serializer)
  }

  private def extendedSyntaxConfigurableSerializerDecoder: Decoder[Option[AuditLogSerializer]] = Decoder.instance { c =>
    for {
      allowedEventMode <- c.downField("verbosity_level_serialization_mode").as[AllowedEventMode]
        .left.map(withAuditingSettingsCreationErrorMessage(msg => s"Configurable serializer is used, but the 'verbosity_level_serialization_mode' setting is invalid: $msg"))
      fields <- c.downField("fields").as[Map[AuditFieldPath, AuditFieldValueDescriptor]]
        .left.map(withAuditingSettingsCreationErrorMessage(msg => s"Configurable serializer is used, but the 'fields' setting is missing or invalid: $msg"))
      serializer = new ConfigurableAuditLogSerializer(allowedEventMode, fields)
    } yield Some(serializer)
  }

  private def extendedSyntaxStaticSerializerDecoder: Decoder[Option[AuditLogSerializer]] = Decoder.instance { c =>
    for {
      fullClassNameOpt <- c.downField("class_name").as[Option[String]]
      serializerOpt <- fullClassNameOpt match {
        case Some(fullClassName) => serializerByClassName(fullClassName)
        case None => Right(None)
      }
    } yield serializerOpt
  }

  private def simpleSyntaxSerializerDecoder: Decoder[Option[AuditLogSerializer]] = Decoder.instance { c =>
    for {
      fullClassNameOpt <- c.downField("serializer").as[Option[String]]
      legacyFullClassNameOpt <- c.downField("audit_serializer").as[Option[String]]
      serializerOpt <- fullClassNameOpt.orElse(legacyFullClassNameOpt) match {
        case Some(fullClassName) => serializerByClassName(fullClassName)
        case None => Right(None)
      }
    } yield serializerOpt
  }

  private def serializerByClassName(className: String): Either[DecodingFailure, Some[AuditLogSerializer]] = {
    createSerializerInstanceFromClassName(className).map(Some(_))
      .left.map(error => DecodingFailure(AclCreationErrorCoders.stringify(error), Nil))
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
          case other =>
            Left(DecodingFailure(AclCreationErrorCoders.stringify(
              auditSettingsError(s"Invalid serializer type '$other', allowed values [static, configurable, ecs]")
            ), Nil))
        }
      case Some(_) | None =>
        Right(SerializerType.SimpleSyntaxStaticSerializer)
    }
  }

  private sealed trait SerializerType

  private object SerializerType {
    case object SimpleSyntaxStaticSerializer extends SerializerType

    case object ExtendedSyntaxStaticSerializer extends SerializerType

    case object ExtendedSyntaxConfigurableSerializer extends SerializerType

    case object EcsSerializer extends SerializerType
  }

  private given ecsSerializerVersionDecoder: Decoder[EcsSerializerVersion] = Decoder.decodeString.map(_.toLowerCase).emap {
    case "v1" => Right(EcsSerializerVersion.V1)
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
  private def createSerializerInstanceFromClassName(fullClassName: String): Either[AuditingSettingsCreationError, AuditLogSerializer] = {
    val clazz = Try(Class.forName(fullClassName)).fold(
      {
        case _: ClassNotFoundException => throw new IllegalStateException(s"Serializer with class name $fullClassName not found.")
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
          Some(serializer)
        case serializer: tech.beshu.ror.audit.EnvironmentAwareAuditLogSerializer =>
          Some(new EnvironmentAwareAuditLogSerializerAdapter(serializer))
        case serializer: tech.beshu.ror.requestcontext.AuditLogSerializer[_] =>
          Some(new DeprecatedAuditLogSerializerAdapter(serializer))
        case _ => None
      }
    } match {
      case Success(Some(customSerializer)) =>
        logger.info(s"Using custom serializer: ${customSerializer.getClass.getName}")
        Right(customSerializer)
      case Success(None) => Left(auditSettingsError(s"Class ${fullClassName.show} is not a subclass of ${classOf[AuditLogSerializer].getName.show} or ${classOf[tech.beshu.ror.requestcontext.AuditLogSerializer[_]].getName.show}"))
      case Failure(ex) => Left(auditSettingsError(s"Cannot create instance of class '${fullClassName.show}', error: ${ex.getMessage.show}"))
    }
  }

  given allowedEventModeDecoder: Decoder[AllowedEventMode] = {
    SyncDecoderCreator
      .from(Decoder[Option[Set[Verbosity]]])
      .map[AllowedEventMode] {
        case Some(verbosityLevels) => AllowedEventMode.Include(verbosityLevels)
        case None => AllowedEventMode.IncludeAll
      }
      .decoder
  }

  private given auditFieldsDecoder: Decoder[Map[AuditFieldPath, AuditFieldValueDescriptor]] =
    Decoder.instance { cursor =>
      def decodeJson(json: Json,
                     path: List[String]): Decoder.Result[Map[List[String], AuditFieldValueDescriptor]] = {
        json.fold(
          jsonNull =
            Left(DecodingFailure("Expected AuditFieldValueDescriptor, got null", cursor.history)),
          jsonBoolean = b =>
            Right(Map(path -> AuditFieldValueDescriptor.BooleanValue(b))),
          jsonNumber = n =>
            n.toBigDecimal
              .map(bd => Map(path -> AuditFieldValueDescriptor.NumericValue(bd)))
              .toRight(DecodingFailure("Cannot decode number", cursor.history)),
          jsonString = s =>
            AuditFieldValueDescriptorParser
              .parse(s)
              .map(desc => Map(path -> desc))
              .left.map(err => DecodingFailure(err, cursor.history)),
          jsonArray = _ =>
            Left(DecodingFailure("AuditFieldValueDescriptor cannot be an array", cursor.history)),
          jsonObject = obj =>
            obj.toList
              .traverse { case (k, v) => decodeJson(v, path :+ k) }
              .map(_.foldLeft(Map.empty[List[String], AuditFieldValueDescriptor])(_ ++ _))
        )
      }

      for {
        rawResult <- decodeJson(cursor.value, Nil)
        result = rawResult.view.map{ case (path, value) =>
          NonEmptyList.fromList(path) match {
            case Some(nonEmptyPath) =>
              AuditFieldPath(nonEmptyPath.head, nonEmptyPath.tail) -> value
            case None =>
              throw new IllegalStateException(s"Empty audit field path encountered when decoding configurable audit fields definition.")
          }
        }.toMap
      } yield result
    }

  given verbosityDecoder: Decoder[Verbosity] = {
    SyncDecoderCreator
      .from(Decoder.decodeString)
      .emap {
        case "ERROR" => Right(Verbosity.Error: Verbosity)
        case "INFO" => Right(Verbosity.Info: Verbosity)
        case other => Left(s"Unknown verbosity level [$other], allowed values are: [ERROR, INFO]")
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
          case other => Left(auditSettingsError(s"Unknown cluster mode [$other], allowed values are: [round-robin]"))
        }
        .decoder

    def clusterCredentialsFromNodesUris(nodes: UniqueNonEmptyList[AuditClusterNode]): Either[AuditingSettingsCreationError, Option[NodeCredentials]] = {
      val (nodesWithoutCredentials: Iterable[AuditClusterNode],
      nodesWithCredentials: Iterable[(AuditClusterNode, NodeCredentials)]) =
        nodes.partitionMap(n => n.credentials.map(c => (n, c)).toRight(n))

      if (nodesWithoutCredentials.size == nodes.size) {
        Right(None)
      } else {
        // check if all nodes have the same credentials - if not, it's possible that different clusters are used
        lazy val inconsistentCredentialsError =
          auditSettingsError(s"One or more audit cluster nodes have inconsistent credentials. Please configure the same credentials. Nodes: ${nodes.map(_.uri.show).toList.show}")
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
        } yield AuditCluster.RemoteAuditCluster(clusterNodes, ClusterMode.RoundRobin, maybeCredentials)
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
                Left(auditSettingsError(s"Audit output configuration is missing the ‘$passwordKey’ field."))
              case (None, Some(pass)) =>
                Left(auditSettingsError(s"Audit output configuration is missing the ‘$usernameKey’ field."))
            }
          }.leftMap(error => DecodingFailure(AclCreationErrorCoders.stringify(error), Nil))
        } yield AuditCluster.RemoteAuditCluster(clusterNodes, mode, maybeCredentials)
    }
  }

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
      auditSettingsError(
        s"Unsupported type of audit output: ${unsupportedType.show}. Supported types: [${supportedTypes.toList.show}]"
      )
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
    def instance: Decoder[Option[AuditingTool.AuditSettings]] = Decoder.instance { c =>
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
      cursor.downField("audit").downFieldAs[Option[T]](key)
        .flatMap {
          case Some(value) => Right(Some(value))
          case None => cursor.downFieldAs[Option[T]](fallbackKey)
        }
    }
  }

}
