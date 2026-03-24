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
import cats.data.{EitherT, NonEmptyList, Validated, ValidatedNel}
import cats.implicits.*
import monix.eval.Task
import org.json.JSONObject
import tech.beshu.ror.accesscontrol.History
import tech.beshu.ror.accesscontrol.audit.AuditingTool.*
import tech.beshu.ror.accesscontrol.audit.AuditingTool.AuditSettings.AuditSink
import tech.beshu.ror.accesscontrol.audit.AuditingTool.AuditSettings.AuditSink.{Disabled, Enabled}
import tech.beshu.ror.accesscontrol.audit.remote.AuditRemoteClusterHealthcheck
import tech.beshu.ror.accesscontrol.audit.sink.*
import tech.beshu.ror.accesscontrol.blocks.Block.Verbosity
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.AuditCluster.*
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory
import tech.beshu.ror.accesscontrol.logging.ResponseContext
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.audit.instances.BlockVerbosityAwareAuditLogSerializer
import tech.beshu.ror.audit.{AuditEnvironmentContext, AuditLogSerializer, AuditRequestContext, AuditResponseContext}
import tech.beshu.ror.es.EsNodeSettings
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.RequestIdAwareLogging

import java.time.Clock

final class AuditingTool private(auditSinks: NonEmptyList[BaseAuditSink])
                                (implicit loggingContext: LoggingContext,
                                 auditEnvironmentContext: AuditEnvironmentContext) {

  def audit[B <: BlockContext](response: ResponseContext[B]): Task[Unit] = {
    val auditResponseContext = toAuditResponse(response, auditEnvironmentContext)
    implicit val requestId: RequestId = response.requestContext.id.toRequestId
    auditSinks
      .parTraverse(_.submit(auditResponseContext))
      .map((_: NonEmptyList[Unit]) => ())
  }

  def close(): Task[Unit] = {
    auditSinks
      .parTraverse(_.close())
      .map((_: NonEmptyList[Unit]) => ())
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
          verbosity = toAuditVerbosity(allowedBy.blockContext.block.verbosity),
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
          verbosity = toAuditVerbosity(Block.Verbosity.Info),
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
          verbosity = toAuditVerbosity(forbiddenBy.blockContext.block.verbosity),
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

  private def toAuditVerbosity(verbosity: Verbosity) = verbosity match {
    case Verbosity.Info => AuditResponseContext.Verbosity.Info
    case Verbosity.Error => AuditResponseContext.Verbosity.Error
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

  final case class AuditSettings(auditSinks: NonEmptyList[AuditSettings.AuditSink],
                                 esNodeSettings: EsNodeSettings)

  object AuditSettings {

    sealed trait AuditSink

    object AuditSink {
      final case class Enabled(config: AuditSink.Config) extends AuditSink

      case object Disabled extends AuditSink

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
            auditCluster = LocalAuditCluster,
          )
        }

        final case class EsDataStreamBasedSink(logSerializer: AuditLogSerializer,
                                               rorAuditDataStream: RorAuditDataStream,
                                               auditCluster: AuditCluster) extends Config

        object EsDataStreamBasedSink {
          val default: EsDataStreamBasedSink = EsDataStreamBasedSink(
            logSerializer = new BlockVerbosityAwareAuditLogSerializer,
            rorAuditDataStream = RorAuditDataStream.default,
            auditCluster = LocalAuditCluster,
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
      }
    }
  }

  final case class CreationError(message: String) extends AnyVal

  def create(settings: AuditSettings,
             auditSinkServiceCreator: AuditSinkServiceCreator,
             httpClientsFactory: HttpClientsFactory)
            (implicit clock: Clock,
             loggingContext: LoggingContext): Task[Either[NonEmptyList[CreationError], Option[AuditingTool]]] = {
    createAuditSinks(settings, auditSinkServiceCreator, httpClientsFactory).map {
      _.map {
          case Some(auditSinks) =>
            implicit val auditEnvironmentContext: AuditEnvironmentContext = new AuditEnvironmentContextBasedOnEsNodeSettings(settings.esNodeSettings)
            noRequestIdLogger.info(s"The audit is enabled with the given outputs: [${auditSinks.toList.show}]")
            Some(new AuditingTool(auditSinks))
          case None =>
            noRequestIdLogger.info("The audit is disabled because no output is enabled")
            None
        }
        .toEither
        .leftMap { errors =>
          errors.map(error => CreationError(error.message))
        }
    }
  }

  private def createAuditSinks(settings: AuditSettings,
                               auditSinkServiceCreator: AuditSinkServiceCreator,
                               httpClientsFactory: HttpClientsFactory)
                              (using Clock): Task[ValidatedNel[CreationError, Option[NonEmptyList[SupportedAuditSink]]]] = {
    settings
      .auditSinks
      .toList
      .map[Task[Either[CreationError, Option[SupportedAuditSink]]]] {
        case Enabled(config: AuditSink.Config.EsIndexBasedSink) =>
          val serviceCreator: IndexBasedAuditSinkServiceCreator = auditSinkServiceCreator match {
            case creator: DataStreamAndIndexBasedAuditSinkServiceCreator => creator
            case creator: IndexBasedAuditSinkServiceCreator => creator
          }
          (for {
            _ <- EitherT(optionalClusterHealthCheck(config.auditCluster, httpClientsFactory))
            auditSink <- EitherT.right(createIndexSink(config, serviceCreator))
          } yield Some(auditSink)).value
        case Enabled(config: AuditSink.Config.EsDataStreamBasedSink) =>
          (for {
            _ <- EitherT(optionalClusterHealthCheck(config.auditCluster, httpClientsFactory))
            auditSink <- auditSinkServiceCreator match {
              case creator: DataStreamAndIndexBasedAuditSinkServiceCreator =>
                EitherT(createDataStreamSink(config, creator))
              case _: IndexBasedAuditSinkServiceCreator =>
                // todo improvement - make this state impossible
                EitherT.liftF[Task, CreationError, SupportedAuditSink](
                  Task.raiseError(new IllegalStateException("Data stream audit sink is not supported in this version"))
                )
            }
          } yield Option(auditSink)).value
        case Enabled(AuditSink.Config.LogBasedSink(serializer, loggerName)) =>
          Task.delay(Right(new LogBasedAuditSink(serializer, loggerName).some))
        case Disabled =>
          Task.pure(Right(None))
      }
      .map(_.map(_.toValidated))
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
        NonEmptyList.fromList(errors).toInvalid(NonEmptyList.fromList(sinks))
      }
  }

  private def createIndexSink(config: AuditSink.Config.EsIndexBasedSink,
                              serviceCreator: IndexBasedAuditSinkServiceCreator)
                             (using Clock): Task[SupportedAuditSink] = Task.delay {
    val service = serviceCreator.index(config.auditCluster)
    EsIndexBasedAuditSink(
      serializer = config.logSerializer,
      indexTemplate = config.rorAuditIndexTemplate,
      auditSinkService = service
    )
  }

  private def createDataStreamSink(config: AuditSink.Config.EsDataStreamBasedSink,
                                   serviceCreator: DataStreamAndIndexBasedAuditSinkServiceCreator): Task[Either[CreationError, SupportedAuditSink]] =
    Task.delay(serviceCreator.dataStream(config.auditCluster))
      .flatMap { auditSinkService =>
        EsDataStreamBasedAuditSink.create(
          config.logSerializer,
          config.rorAuditDataStream,
          auditSinkService,
          config.auditCluster
        ).map(_.leftMap(error => CreationError(error.message)))
      }

  private def optionalClusterHealthCheck(cluster: AuditCluster,
                                         httpClientsFactory: HttpClientsFactory): Task[Either[CreationError, Unit]] = {
    cluster match {
      case cluster: RemoteAuditCluster if !cluster.ignoreClusterConnectivityProblems =>
        new AuditRemoteClusterHealthcheck(httpClientsFactory)
          .check(cluster)
          .map(_.leftMap(error => CreationError(error.message)))
      case AuditCluster.LocalAuditCluster | _: AuditCluster.RemoteAuditCluster =>
        Task.pure(Right(()))
    }
  }

  private type SupportedAuditSink = EsIndexBasedAuditSink | EsDataStreamBasedAuditSink | LogBasedAuditSink

  private given Show[SupportedAuditSink] = Show.show {
    case _: EsIndexBasedAuditSink => "index"
    case _: LogBasedAuditSink => "log"
    case _: EsDataStreamBasedAuditSink => "data_stream"
  }

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
