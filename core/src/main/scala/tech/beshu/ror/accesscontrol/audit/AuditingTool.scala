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
import monix.eval.Task
import org.json.JSONObject
import tech.beshu.ror.accesscontrol.History
import tech.beshu.ror.accesscontrol.audit.AuditingTool.*
import tech.beshu.ror.accesscontrol.audit.AuditingTool.AuditSettings.AuditSink
import tech.beshu.ror.accesscontrol.audit.AuditingTool.AuditSettings.AuditSink.{Disabled, Enabled, ExplicitlyDisabledAcl}
import tech.beshu.ror.accesscontrol.audit.sink.*
import tech.beshu.ror.accesscontrol.blocks.Block.Audit
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.logging.ResponseContext
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.audit.instances.BlockVerbosityAwareAuditLogSerializer
import tech.beshu.ror.audit.{AuditEnvironmentContext, AuditLogSerializer, AuditRequestContext, AuditResponseContext}
import tech.beshu.ror.es.EsNodeSettings
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.RequestIdAwareLogging
import eu.timepit.refined.types.numeric.PosInt

import java.time.Clock

final class AuditingTool private(auditSinks: List[BaseAuditSink])
                                (implicit loggingContext: LoggingContext,
                                 auditEnvironmentContext: AuditEnvironmentContext) {

  def audit[B <: BlockContext](response: ResponseContext[B]): Task[Unit] = {
    val auditResponseContext = toAuditResponse(response, auditEnvironmentContext)
    implicit val requestId: RequestId = response.requestContext.id.toRequestId
    NonEmptyList.fromList(activeSinksFor(response)) match {
      case Some(nel) => nel.parTraverse(_.submit(auditResponseContext)).map(_ => ())
      case None      => Task.unit
    }
  }

  private def activeSinksFor[B <: BlockContext](response: ResponseContext[B]): List[BaseAuditSink] = {
    val blockAuditEnabled: Option[Audit.Enabled] = response match {
      case allowedBy: ResponseContext.AllowedBy[B] =>
        allowedBy.blockContext.block.audit match { case e: Audit.Enabled => Some(e); case _ => None }
      case forbiddenBy: ResponseContext.ForbiddenBy[B] =>
        forbiddenBy.blockContext.block.audit match { case e: Audit.Enabled => Some(e); case _ => None }
      case _ => None
    }
    filterSinks(blockAuditEnabled)
  }

  private def filterSinks(blockAudit: Option[Audit.Enabled]): List[BaseAuditSink] = {
    blockAudit match {
      case None => auditSinks
      case Some(config) if config.enabledSinks.isEmpty && config.disabledSinks.isEmpty =>
        auditSinks
      case Some(config) =>
        auditSinks.filter { sink =>
          config.enabledSinks match {
            case Some(names) => names.contains(sink.name)
            case None =>
              config.disabledSinks match {
                case Some(names) => !names.contains(sink.name)
                case None        => true
              }
          }
        }
    }
  }

  def close(): Task[Unit] = {
    NonEmptyList.fromList(auditSinks) match {
      case Some(nel) => nel.parTraverse(_.close()).map((_: NonEmptyList[Unit]) => ())
      case None      => Task.unit
    }
  }

  private def toAuditResponse[B <: BlockContext](responseContext: ResponseContext[B], auditEnvironmentContext: AuditEnvironmentContext): AuditResponseContext = {
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
            generalAuditEvents = allowedBy.requestContext.generalAuditEvents
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
            generalAuditEvents = allow.requestContext.generalAuditEvents
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
            historyEntries = forbiddenBy.history),
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
            historyEntries = forbidden.history
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
            historyEntries = requestedIndexNotExist.history
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
            historyEntries = History.empty
          ),
          cause = errored.cause
        )
    }
  }

  private def toAuditVerbosity(audit: Audit): AuditResponseContext.Verbosity = audit match {
    case e: Audit.Enabled =>
      if (e.logAllowedEvents) AuditResponseContext.Verbosity.Info else AuditResponseContext.Verbosity.Error
    case Audit.Disabled =>
      AuditResponseContext.Verbosity.Error
  }

  private def toAuditRequestContext[B <: BlockContext](requestContext: RequestContext.Aux[B],
                                                       loggedUser: Option[LoggedUser],
                                                       auditEnvironmentContext: AuditEnvironmentContext,
                                                       blockContext: Option[B],
                                                       matchedBlocks: Option[NonEmptyList[Block]],
                                                       historyEntries: History[B],
                                                       generalAuditEvents: JSONObject = new JSONObject()): AuditRequestContext = {
    new AuditRequestContextBasedOnAclResult(
      requestContext,
      loggedUser,
      matchedBlocks,
      historyEntries,
      loggingContext,
      auditEnvironmentContext,
      generalAuditEvents,
      involvesIndices(blockContext)
    )
  }

  private def involvesIndices[B <: BlockContext](blockContext: Option[B]) =
    blockContext.exists(_.involvesIndices)

}

object AuditingTool extends RequestIdAwareLogging {

  final case class AuditSettings(auditSinks: NonEmptyList[AuditSettings.AuditSink])

  object AuditSettings {

    sealed trait AuditSink

    object AuditSink {
      final case class Enabled(name: Block.SinkName, config: AuditSink.Config) extends AuditSink

      case object Disabled extends AuditSink

      case object ExplicitlyDisabledAcl extends AuditSink

      sealed trait Config {
        def logSerializer: AuditLogSerializer
      }

      object Config {
        final case class EsIndexBasedSink(logSerializer: AuditLogSerializer,
                                          rorAuditIndexTemplate: RorAuditIndexTemplate,
                                          auditCluster: AuditCluster) extends Config

        object EsIndexBasedSink {
          val default: EsIndexBasedSink = EsIndexBasedSink(
            logSerializer = new BlockVerbosityAwareAuditLogSerializer,
            rorAuditIndexTemplate = RorAuditIndexTemplate.default,
            auditCluster = AuditCluster.LocalAuditCluster,
          )
        }

        final case class EsDataStreamBasedSink(logSerializer: AuditLogSerializer,
                                               rorAuditDataStream: RorAuditDataStream,
                                               auditCluster: AuditCluster) extends Config

        object EsDataStreamBasedSink {
          val default: EsDataStreamBasedSink = EsDataStreamBasedSink(
            logSerializer = new BlockVerbosityAwareAuditLogSerializer,
            rorAuditDataStream = RorAuditDataStream.default,
            auditCluster = AuditCluster.LocalAuditCluster,
          )
        }

        final case class LogBasedSink(logSerializer: AuditLogSerializer,
                                      loggerName: RorAuditLoggerName) extends Config

        object LogBasedSink {
          val default: LogBasedSink = LogBasedSink(
            logSerializer = new BlockVerbosityAwareAuditLogSerializer,
            loggerName = RorAuditLoggerName.default
          )
        }

        final case class RollingFileBasedSink(logSerializer: AuditLogSerializer,
                                              loggerName: RorAuditLoggerName,
                                              fileAppender: RollingFileBasedSink.FileAppenderConfig) extends Config

        object RollingFileBasedSink {
          final case class FileAppenderConfig(filePath: java.nio.file.Path,
                                              maxFileSize: FileSize,
                                              maxFiles: PosInt)
        }
      }
    }
  }

  sealed trait AuditOutputsConfig

  object AuditOutputsConfig {
    case object NoOutputsConfigured extends AuditOutputsConfig
    final case class WithOutputs(auditSinks: NonEmptyList[AuditSettings.AuditSink]) extends AuditOutputsConfig
  }

  final case class CreationError(message: String) extends AnyVal

  def create(settings: Option[AuditOutputsConfig],
             esNodeSettings: EsNodeSettings,
             auditSinkServiceCreator: AuditSinkServiceCreator)
            (implicit clock: Clock,
             loggingContext: LoggingContext): Task[Either[NonEmptyList[CreationError], AuditingTool]] = {
    val effectiveSinks = applyDefaults(settings)
    createAuditSinks(effectiveSinks, auditSinkServiceCreator).map {
      _.map { auditSinks =>
          implicit val auditEnvironmentContext: AuditEnvironmentContext = new AuditEnvironmentContextBasedOnEsNodeSettings(esNodeSettings)
          if (auditSinks.isEmpty) {
            noRequestIdLogger.info("The audit is disabled because no output is enabled")
          } else {
            noRequestIdLogger.info(s"The audit is enabled with the given outputs: [${auditSinks.show}]")
          }
          new AuditingTool(auditSinks)
        }
        .toEither
        .leftMap { errors =>
          errors.map(error => CreationError(error.message))
        }
    }
  }

  private def applyDefaults(settings: Option[AuditOutputsConfig]): NonEmptyList[AuditSink] = settings match {
    case None =>
      NonEmptyList.one(defaultAclSink)
    case Some(AuditOutputsConfig.NoOutputsConfigured) =>
      ensureAclSink(NonEmptyList.one(defaultIndexStorageSink))
    case Some(AuditOutputsConfig.WithOutputs(sinks)) =>
      ensureAclSink(sinks)
  }

  private def ensureAclSink(sinks: NonEmptyList[AuditSink]): NonEmptyList[AuditSink] = {
    val hasAcl = sinks.exists {
      case AuditSink.Enabled(_, s: AuditSink.Config.LogBasedSink) =>
        s.logSerializer.isInstanceOf[AclAuditLogSerializer]
      case AuditSink.ExplicitlyDisabledAcl => true
      case _                               => false
    }
    if (hasAcl) sinks else defaultAclSink :: sinks
  }

  private def defaultAclSink = AuditSink.Enabled(
    Block.SinkName.random(),
    AuditSink.Config.LogBasedSink(new AclAuditLogSerializer, RorAuditLoggerName.default)
  )

  private def defaultIndexStorageSink = AuditSink.Enabled(
    Block.SinkName.random(),
    AuditSink.Config.EsIndexBasedSink.default
  )

  private def createAuditSinks(sinks: NonEmptyList[AuditSink],
                               auditSinkServiceCreator: AuditSinkServiceCreator)
                              (using Clock): Task[ValidatedNel[CreationError, List[SupportedAuditSink]]] = {
    sinks
      .toList
      .map[Task[Validated[CreationError, Option[SupportedAuditSink]]]] {
        case Enabled(name, config: AuditSink.Config.EsIndexBasedSink) =>
          val serviceCreator: IndexBasedAuditSinkServiceCreator = auditSinkServiceCreator match {
            case creator: DataStreamAndIndexBasedAuditSinkServiceCreator => creator
            case creator: IndexBasedAuditSinkServiceCreator => creator
          }
          createIndexSink(name, config, serviceCreator).map(_.some.valid)
        case Enabled(name, config: AuditSink.Config.EsDataStreamBasedSink) =>
          auditSinkServiceCreator match {
            case creator: DataStreamAndIndexBasedAuditSinkServiceCreator =>
              createDataStreamSink(name, config, creator).map(_.map(_.some))
            case _: IndexBasedAuditSinkServiceCreator =>
              // todo improvement - make this state impossible
              Task.raiseError(new IllegalStateException("Data stream audit sink is not supported in this version"))
          }
        case Enabled(name, config: AuditSink.Config.LogBasedSink) =>
          Task.delay(new LogBasedAuditSink(name, config.logSerializer, config.loggerName).some.valid)
        case Enabled(name, config: AuditSink.Config.RollingFileBasedSink) =>
          Task.delay(new RollingFileBasedAuditSink(name, config.logSerializer, config.loggerName, config.fileAppender).some.valid)
        case Disabled | ExplicitlyDisabledAcl =>
          Task.pure(None.valid)
      }
      .sequence
      .map { sinkCreationResults =>
        sinkCreationResults.foldLeft[(List[CreationError], List[SupportedAuditSink])](List.empty, List.empty) {
          case ((errorsAcc, sinksAcc), result) =>
            result match {
              case Validated.Valid(Some(auditSink)) => (errorsAcc, sinksAcc :+ auditSink)
              case Validated.Valid(None) => (errorsAcc, sinksAcc)
              case Validated.Invalid(error) => (errorsAcc :+ error, sinksAcc)
            }
        }
      }
      .map { case (errors, sinks) =>
        NonEmptyList.fromList(errors).toInvalid(sinks)
      }
  }

  private def createIndexSink(name: Block.SinkName,
                              config: AuditSink.Config.EsIndexBasedSink,
                              serviceCreator: IndexBasedAuditSinkServiceCreator)
                             (using Clock): Task[SupportedAuditSink] = Task.delay {
    val service = serviceCreator.index(config.auditCluster)
    EsIndexBasedAuditSink(
      sinkName = name,
      serializer = config.logSerializer,
      indexTemplate = config.rorAuditIndexTemplate,
      auditSinkService = service
    )
  }

  private def createDataStreamSink(name: Block.SinkName,
                                   config: AuditSink.Config.EsDataStreamBasedSink,
                                   serviceCreator: DataStreamAndIndexBasedAuditSinkServiceCreator): Task[Validated[CreationError, SupportedAuditSink]] =
    Task.delay(serviceCreator.dataStream(config.auditCluster))
      .flatMap { auditSinkService =>
        EsDataStreamBasedAuditSink.create(
          name,
          config.logSerializer,
          config.rorAuditDataStream,
          auditSinkService,
          config.auditCluster
        ).map(_.leftMap(error => CreationError(error.message)).toValidated)
      }

  private type SupportedAuditSink = EsIndexBasedAuditSink | EsDataStreamBasedAuditSink | LogBasedAuditSink | RollingFileBasedAuditSink

  private given showSupportedAuditSink: Show[SupportedAuditSink] = Show.show {
    case _: EsIndexBasedAuditSink => "index"
    case _: LogBasedAuditSink => "log"
    case _: RollingFileBasedAuditSink => "log_file"
    case _: EsDataStreamBasedAuditSink => "data_stream"
  }

  private given Show[List[SupportedAuditSink]] = sinks => sinks.map(_.show).mkString(", ")

  extension (userMetadata: UserMetadata) {
    def loggedUser: LoggedUser = userMetadata match {
      case UserMetadata.WithoutGroups(loggedUser, _, _, _) => loggedUser
      case UserMetadata.WithGroups(groupsMetadata) => groupsMetadata.values.head.loggedUser
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
