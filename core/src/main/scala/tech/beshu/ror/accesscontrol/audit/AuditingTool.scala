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
package tech.beshu.ror.accesscontrol.audit

import cats.Show
import cats.data.{NonEmptyList, Validated, ValidatedNel}
import cats.implicits.*
import eu.timepit.refined.types.numeric.PosInt
import monix.eval.Task
import org.json.JSONObject
import squants.information.Information
import tech.beshu.ror.accesscontrol.History
import tech.beshu.ror.accesscontrol.audit.AuditingTool.*
import tech.beshu.ror.accesscontrol.audit.AuditingTool.AuditSettings.AuditSink.{Disabled, Enabled}
import tech.beshu.ror.accesscontrol.audit.acl.AclAuditLogSerializer
import tech.beshu.ror.accesscontrol.audit.sink.*
import tech.beshu.ror.accesscontrol.blocks.Block.Audit
import tech.beshu.ror.accesscontrol.blocks.Block.Audit.Enabled.PrecomputedAuditSinks
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.logging.ResponseContext
import tech.beshu.ror.accesscontrol.logging.ResponseContext.*
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.audit.instances.BlockVerbosityAwareAuditLogSerializer
import tech.beshu.ror.audit.{AuditEnvironmentContext, AuditRequestContext, AuditResponseContext}
import tech.beshu.ror.es.EsNodeSettings
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.RequestIdAwareLogging

import java.time.Clock

final class AuditingTool private (private[ror] val sinks: List[AuditSink])(
    implicit loggingContext: LoggingContext,
    auditEnvironmentContext: AuditEnvironmentContext
) {

  def audit[B <: BlockContext](response: ResponseContext[B]): Task[Unit] = {
    NonEmptyList.fromList(activeSinksFor(response)) match {
      case Some(nel) =>
        val auditResponseContext = toAuditResponse(response, auditEnvironmentContext)
        implicit val requestId: RequestId = response.requestContext.id.toRequestId
        nel.parTraverse(_.submit(auditResponseContext)).map(_ => ())
      case None =>
        Task.unit
    }
  }

  private def activeSinksFor[B <: BlockContext](responseContext: ResponseContext[B]): List[AuditSink] = {
    responseContext match {
      case AllowedBy(_, blockContext, _) =>
        auditSinksFor(blockContext.block)
      case Allowed(_, UserMetadata.WithoutGroups(_, _, _, metadataOrigin), _) =>
        auditSinksFor(metadataOrigin.blockContext.block)
      case Allowed(_, UserMetadata.WithGroups(groupsMetadata), _) =>
        groupsMetadata.values.toList
          .map(_.metadataOrigin.blockContext.block)
          .flatMap(auditSinksFor)
          .distinct
      case ForbiddenBy(_, blockContext, _) => auditSinksFor(blockContext.block)
      case Forbidden(_, _)                 => sinks
      case RequestedIndexNotExist(_, _)    => sinks
      case Errored(_, _)                   => sinks
    }
  }

  private def auditSinksFor(block: Block): List[AuditSink] = block.audit match {
    case Audit.Enabled(_, _, PrecomputedAuditSinks.Available(auditSinks)) => auditSinks
    case Audit.Enabled(_, _, PrecomputedAuditSinks.NotAvailable)          => Nil
    case Audit.Disabled                                                   => Nil
  }

  def close(): Task[Unit] = sinks.traverse(_.close()).void

  private def toAuditResponse[B <: BlockContext](
      responseContext: ResponseContext[B],
      auditEnvironmentContext: AuditEnvironmentContext
  ): AuditResponseContext = {
    responseContext match {
      case allowedBy: ResponseContext.AllowedBy[B] =>
        AuditResponseContext.Allowed(
          requestContext = toAuditRequestContext(
            requestContext = allowedBy.requestContext,
            loggedUser = allowedBy.blockContext.blockMetadata.loggedUser,
            auditEnvironmentContext = auditEnvironmentContext,
            blockContext = Some(allowedBy.blockContext),
            matchedBlocks = Some(NonEmptyList.one(allowedBy.blockContext.block)),
            historyEntries = allowedBy.history,
            generalAuditEvents = allowedBy.requestContext.generalAuditEvents,
            responseContext = responseContext,
          ),
          verbosity = toAuditVerbosity(allowedBy.blockContext.block.audit),
          reason = allowedBy.blockContext.block.show
        )
      case allow: ResponseContext.Allowed[B] =>
        AuditResponseContext.Allowed(
          requestContext = toAuditRequestContext(
            requestContext = allow.requestContext,
            loggedUser = Some(allow.userMetadata.loggedUser),
            auditEnvironmentContext = auditEnvironmentContext,
            blockContext = None,
            matchedBlocks = Some(allow.userMetadata.matchedBlocks),
            historyEntries = allow.history,
            generalAuditEvents = allow.requestContext.generalAuditEvents,
            responseContext = responseContext,
          ),
          verbosity = AuditResponseContext.Verbosity.Info,
          reason = allow.userMetadata.reason,
        )
      case forbiddenBy: ResponseContext.ForbiddenBy[B] =>
        AuditResponseContext.ForbiddenBy(
          requestContext = toAuditRequestContext(
            requestContext = forbiddenBy.requestContext,
            loggedUser = forbiddenBy.blockContext.blockMetadata.loggedUser,
            auditEnvironmentContext = auditEnvironmentContext,
            blockContext = Some(forbiddenBy.blockContext),
            matchedBlocks = Some(NonEmptyList.one(forbiddenBy.blockContext.block)),
            historyEntries = forbiddenBy.history,
            responseContext = responseContext,
          ),
          verbosity = toAuditVerbosity(forbiddenBy.blockContext.block.audit),
          reason = forbiddenBy.blockContext.block.show
        )
      case forbidden: ResponseContext.Forbidden[B] =>
        AuditResponseContext.Forbidden(
          requestContext = toAuditRequestContext(
            requestContext = forbidden.requestContext,
            loggedUser = None,
            auditEnvironmentContext = auditEnvironmentContext,
            blockContext = None,
            matchedBlocks = None,
            historyEntries = forbidden.history,
            responseContext = responseContext,
          )
        )
      case requestedIndexNotExist: ResponseContext.RequestedIndexNotExist[B] =>
        AuditResponseContext.RequestedIndexNotExist(
          requestContext = toAuditRequestContext(
            requestContext = requestedIndexNotExist.requestContext,
            loggedUser = None, // todo: in RORDEV-1922 consider this potential problem
            auditEnvironmentContext = auditEnvironmentContext,
            blockContext = None,
            matchedBlocks = None,
            historyEntries = requestedIndexNotExist.history,
            responseContext = responseContext,
          )
        )
      case errored: ResponseContext.Errored[B] =>
        AuditResponseContext.Errored(
          requestContext = toAuditRequestContext(
            requestContext = errored.requestContext,
            loggedUser = None,
            auditEnvironmentContext = auditEnvironmentContext,
            blockContext = None,
            matchedBlocks = None,
            historyEntries = History.empty,
            responseContext = responseContext,
          ),
          cause = errored.cause
        )
    }
  }

  private def toAuditVerbosity(audit: Audit): AuditResponseContext.Verbosity = audit match {
    case Audit.Enabled(logAllowedEvents, _, _) =>
      if (logAllowedEvents) AuditResponseContext.Verbosity.Info else AuditResponseContext.Verbosity.Error
    case Audit.Disabled =>
      AuditResponseContext.Verbosity.Info
  }

  private def toAuditRequestContext[B <: BlockContext](
      requestContext: RequestContext.Aux[B],
      loggedUser: Option[LoggedUser],
      auditEnvironmentContext: AuditEnvironmentContext,
      blockContext: Option[B],
      matchedBlocks: Option[NonEmptyList[Block]],
      historyEntries: History[B],
      responseContext: ResponseContext[B],
      generalAuditEvents: JSONObject = new JSONObject()
  ): AuditRequestContext = {
    new AuditRequestContextBasedOnAclResult(
      requestContext,
      loggedUser,
      matchedBlocks,
      historyEntries,
      loggingContext,
      responseContext,
      auditEnvironmentContext,
      generalAuditEvents,
      involvesIndices(blockContext),
    )
  }

  private def involvesIndices[B <: BlockContext](blockContext: Option[B]) =
    blockContext.exists(_.involvesIndices)

}

object AuditingTool extends RequestIdAwareLogging {

  object AuditSettings {

    sealed trait AuditSink

    object AuditSink {
      final case class Enabled(name: SinkName, config: AuditSettings.AuditSink.Config) extends AuditSink

      case object Disabled extends AuditSink

      sealed trait Config

      object Config {

        final case class EsIndexBasedSink(
            serializer: JsonAuditSerializer,
            rorAuditIndexTemplate: RorAuditIndexTemplate,
            auditCluster: AuditCluster,
            pipeline: Option[String] = None
        ) extends Config

        object EsIndexBasedSink {

          val default: EsIndexBasedSink = EsIndexBasedSink(
            serializer = AuditSerializer.Delegating(new BlockVerbosityAwareAuditLogSerializer),
            rorAuditIndexTemplate = RorAuditIndexTemplate.default,
            auditCluster = AuditCluster.LocalAuditCluster,
            pipeline = None,
          )

        }

        final case class EsDataStreamBasedSink(
            serializer: JsonAuditSerializer,
            rorAuditDataStream: RorAuditDataStream,
            auditCluster: AuditCluster,
            pipeline: Option[String] = None
        ) extends Config

        object EsDataStreamBasedSink {

          val default: EsDataStreamBasedSink = EsDataStreamBasedSink(
            serializer = AuditSerializer.Delegating(new BlockVerbosityAwareAuditLogSerializer),
            rorAuditDataStream = RorAuditDataStream.default,
            auditCluster = AuditCluster.LocalAuditCluster,
            pipeline = None,
          )

        }

        final case class LogBasedSink(serializer: AuditSerializer, loggerName: RorAuditLoggerName) extends Config

        object LogBasedSink {

          val default: LogBasedSink = LogBasedSink(
            serializer = AuditSerializer.Delegating(new BlockVerbosityAwareAuditLogSerializer),
            loggerName = RorAuditLoggerName.default
          )

        }

        final case class RollingFileBasedSink(
            serializer: AuditSerializer,
            loggerName: RorAuditLoggerName,
            fileAppender: RollingFileBasedSink.FileAppenderConfig
        ) extends Config

        object RollingFileBasedSink {
          final case class FileAppenderConfig(filePath: java.nio.file.Path, maxFileSize: Information, maxFiles: PosInt)
        }

      }

    }

  }

  sealed trait AuditOutputsConfig

  object AuditOutputsConfig {
    case object NoOutputsConfigured extends AuditOutputsConfig
    final case class WithOutputs(auditSinks: NonEmptyList[AuditSettings.AuditSink]) extends AuditOutputsConfig
  }

  final case class AuditingConfig(
      outputsConfig: Option[AuditOutputsConfig],
      defaultAclLog: Boolean,
      esNodeSettings: EsNodeSettings
  )

  final case class CreationError(message: String) extends AnyVal

  def create(config: AuditingConfig, auditSinkServiceCreator: AuditSinkServiceCreator)(
      implicit clock: Clock,
      loggingContext: LoggingContext
  ): Task[Either[NonEmptyList[CreationError], AuditingTool]] = {
    val effectiveSinks = applyDefaults(config.outputsConfig, config.defaultAclLog)
    createAuditSinks(effectiveSinks, auditSinkServiceCreator).map {
      _.map { auditSinks =>
        implicit val auditEnvironmentContext: AuditEnvironmentContext =
          new AuditEnvironmentContextBasedOnEsNodeSettings(config.esNodeSettings)
        if (auditSinks.isEmpty) {
          noRequestIdLogger.info("The audit is disabled because no output is enabled")
        } else {
          noRequestIdLogger.info(s"The audit is enabled with the given outputs: [${auditSinks.show}]")
        }
        new AuditingTool(auditSinks)
      }.toEither
        .leftMap { errors =>
          errors.map(error => CreationError(error.message))
        }
    }
  }

  private def applyDefaults(
      settings: Option[AuditOutputsConfig],
      defaultAclLog: Boolean
  ): List[AuditSettings.AuditSink] = {
    val sinks = settings match {
      case None                                         => List.empty
      case Some(AuditOutputsConfig.NoOutputsConfigured) => List(defaultIndexStorageSink)
      case Some(AuditOutputsConfig.WithOutputs(sinks))  => sinks.toList
    }
    if (defaultAclLog) defaultAclSink :: sinks else sinks
  }

  private def defaultAclSink = AuditSettings.AuditSink.Enabled(
    SinkName.defaultAclLog,
    AuditSettings.AuditSink.Config.LogBasedSink(AuditSerializer.Acl, AclAuditLogSerializer.defaultLoggerName)
  )

  private def defaultIndexStorageSink = AuditSettings.AuditSink.Enabled(
    SinkName.defaultIndexStorage,
    AuditSettings.AuditSink.Config.EsIndexBasedSink.default
  )

  private def createAuditSinks(sinks: List[AuditSettings.AuditSink], auditSinkServiceCreator: AuditSinkServiceCreator)(
      using Clock
  ): Task[ValidatedNel[CreationError, List[SupportedAuditSink]]] = {
    sinks
      .map[Task[Validated[CreationError, Option[SupportedAuditSink]]]] {
        case Enabled(name, config: AuditSettings.AuditSink.Config.EsIndexBasedSink) =>
          val serviceCreator: IndexBasedAuditSinkServiceCreator = auditSinkServiceCreator match {
            case creator: DataStreamAndIndexBasedAuditSinkServiceCreator => creator
            case creator: IndexBasedAuditSinkServiceCreator              => creator
          }
          createIndexSink(name, config, serviceCreator).map(_.some.valid)
        case Enabled(name, config: AuditSettings.AuditSink.Config.EsDataStreamBasedSink) =>
          auditSinkServiceCreator match {
            case creator: DataStreamAndIndexBasedAuditSinkServiceCreator =>
              createDataStreamSink(name, config, creator).map(_.map(_.some))
            case _: IndexBasedAuditSinkServiceCreator =>
              // todo improvement - make this state impossible
              Task.raiseError(new IllegalStateException("Data stream audit sink is not supported in this version"))
          }
        case Enabled(name, config: AuditSettings.AuditSink.Config.LogBasedSink) =>
          Task.delay(new LogBasedAuditSink(name, config.serializer, config.loggerName).some.valid)
        case Enabled(name, config: AuditSettings.AuditSink.Config.RollingFileBasedSink) =>
          RollingFileBasedAuditSink
            .create(name, config.serializer, config.loggerName, config.fileAppender)
            .map(_.map(_.some).leftMap(e => CreationError(e.message)).toValidated)
        case Disabled =>
          Task.pure(None.valid)
      }
      .sequence
      .map { sinkCreationResults =>
        sinkCreationResults.foldLeft[(List[CreationError], List[SupportedAuditSink])](List.empty, List.empty) {
          case ((errorsAcc, sinksAcc), result) =>
            result match {
              case Validated.Valid(Some(auditSink)) => (errorsAcc, sinksAcc :+ auditSink)
              case Validated.Valid(None)            => (errorsAcc, sinksAcc)
              case Validated.Invalid(error)         => (errorsAcc :+ error, sinksAcc)
            }
        }
      }
      .map { case (errors, sinks) =>
        NonEmptyList.fromList(errors).toInvalid(sinks)
      }
  }

  private def createIndexSink(
      name: SinkName,
      config: AuditSettings.AuditSink.Config.EsIndexBasedSink,
      serviceCreator: IndexBasedAuditSinkServiceCreator
  )(
      using Clock
  ): Task[SupportedAuditSink] = Task.delay {
    val service = serviceCreator.index(config.auditCluster)
    EsIndexBasedAuditSink(
      sinkName = name,
      serializer = config.serializer,
      indexTemplate = config.rorAuditIndexTemplate,
      auditSinkService = service,
      pipeline = config.pipeline
    )
  }

  private def createDataStreamSink(
      name: SinkName,
      config: AuditSettings.AuditSink.Config.EsDataStreamBasedSink,
      serviceCreator: DataStreamAndIndexBasedAuditSinkServiceCreator
  ): Task[Validated[CreationError, SupportedAuditSink]] =
    Task
      .delay(serviceCreator.dataStream(config.auditCluster))
      .flatMap { auditSinkService =>
        EsDataStreamBasedAuditSink
          .create(
            name,
            config.serializer,
            config.rorAuditDataStream,
            auditSinkService,
            config.auditCluster,
            config.pipeline
          )
          .map(_.leftMap(error => CreationError(error.message)).toValidated)
      }

  private type SupportedAuditSink = EsIndexBasedAuditSink | EsDataStreamBasedAuditSink | LogBasedAuditSink |
    RollingFileBasedAuditSink

  private given showSupportedAuditSink: Show[SupportedAuditSink] = Show.show {
    case _: EsIndexBasedAuditSink      => "index"
    case _: LogBasedAuditSink          => "log"
    case _: RollingFileBasedAuditSink  => "log_file"
    case _: EsDataStreamBasedAuditSink => "data_stream"
  }

  private given Show[List[SupportedAuditSink]] = sinks => sinks.map(_.show).mkString(", ")

  extension (userMetadata: UserMetadata) {

    def loggedUser: LoggedUser = userMetadata match {
      case UserMetadata.WithoutGroups(loggedUser, _, _, _) => loggedUser
      case UserMetadata.WithGroups(groupsMetadata)         => groupsMetadata.values.head.loggedUser
    }

    def matchedBlocks: NonEmptyList[Block] = userMetadata match {
      case UserMetadata.WithoutGroups(_, _, _, metadataOrigin) =>
        NonEmptyList.one(metadataOrigin.blockContext.block)
      case UserMetadata.WithGroups(groupsMetadata) =>
        groupsMetadata.values.map(_.metadataOrigin.blockContext.block).distinctBy(_.name.value)
    }

    def reason: String = userMetadata match {
      case UserMetadata.WithoutGroups(_, _, _, metadataOrigin) =>
        metadataOrigin.blockContext.block.show
      case UserMetadata.WithGroups(groupsMetadata) =>
        groupsMetadata.values.map(_.metadataOrigin.blockContext.block).toList.show
    }

  }

}
