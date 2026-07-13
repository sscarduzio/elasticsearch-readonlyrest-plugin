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
import cats.data.{EitherT, NonEmptyList}
import cats.implicits.*
import monix.eval.Task
import org.json.JSONObject
import tech.beshu.ror.accesscontrol.History
import tech.beshu.ror.accesscontrol.audit.AuditingTool.*
import tech.beshu.ror.accesscontrol.audit.AuditingTool.AuditSettings.AuditSink
import tech.beshu.ror.accesscontrol.audit.AuditingTool.AuditSettings.AuditSink.{Config, Disabled, Enabled}
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

final class AuditingTool private (auditSinks: NonEmptyList[BaseAuditSink])(
    implicit loggingContext: LoggingContext,
    auditEnvironmentContext: AuditEnvironmentContext
) {

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
            historyEntries = forbiddenBy.history
          ),
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
    case Verbosity.Info  => AuditResponseContext.Verbosity.Info
    case Verbosity.Error => AuditResponseContext.Verbosity.Error
  }

  private def toAuditRequestContext[B <: BlockContext](
      requestContext: RequestContext.Aux[B],
      loggedUser: Option[LoggedUser],
      auditEnvironmentContext: AuditEnvironmentContext,
      blockContext: Option[B],
      matchedBlocks: Option[NonEmptyList[Block]],
      historyEntries: History[B],
      generalAuditEvents: JSONObject = new JSONObject()
  ): AuditRequestContext = {
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

  final case class AuditSettings[+C](auditSinks: NonEmptyList[AuditSink[C]], esNodeSettings: EsNodeSettings)

  object AuditSettings {

    type Standard = AuditSettings[AuditSink.Config.Standard]
    type Legacy = AuditSettings[AuditSink.Config.Legacy]

    sealed trait AuditSink[+C]

    object AuditSink {
      final case class Enabled[C <: Config](config: C) extends AuditSink[C]

      case object Disabled extends AuditSink[Nothing]

      sealed trait Config {
        def logSerializer: AuditLogSerializer
      }

      object Config {
        sealed trait Standard extends Config
        sealed trait Legacy extends Config

        final case class EsIndexBasedSink(
            logSerializer: AuditLogSerializer,
            rorAuditIndexTemplate: RorAuditIndexTemplate,
            auditCluster: AuditCluster
        ) extends Standard
            with Legacy

        object EsIndexBasedSink {

          val default: EsIndexBasedSink = EsIndexBasedSink(
            logSerializer = new BlockVerbosityAwareAuditLogSerializer,
            rorAuditIndexTemplate = RorAuditIndexTemplate.default,
            auditCluster = LocalAuditCluster,
          )

        }

        final case class EsDataStreamBasedSink(
            logSerializer: AuditLogSerializer,
            rorAuditDataStream: RorAuditDataStream,
            auditCluster: AuditCluster
        ) extends Standard

        object EsDataStreamBasedSink {

          val default: EsDataStreamBasedSink = EsDataStreamBasedSink(
            logSerializer = new BlockVerbosityAwareAuditLogSerializer,
            rorAuditDataStream = RorAuditDataStream.default,
            auditCluster = LocalAuditCluster,
          )

        }

        final case class LogBasedSink(logSerializer: AuditLogSerializer, loggerName: RorAuditLoggerName)
            extends Standard
            with Legacy

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

  def create(
      settings: AuditSettings.Legacy,
      creator: IndexBasedAuditSinkServiceCreator,
      httpClientsFactory: HttpClientsFactory
  )(
      using Clock,
      LoggingContext
  ): Task[Either[NonEmptyList[CreationError], Option[AuditingTool]]] = {
    val sinkTasks = settings.auditSinks.toList.flatMap {
      case Enabled(config: AuditSink.Config.EsIndexBasedSink) =>
        Some(createIndexSink(config, creator, httpClientsFactory))
      case Enabled(AuditSink.Config.LogBasedSink(serializer, loggerName)) =>
        Some(createLogSink(serializer, loggerName))
      case Disabled =>
        None
    }
    createAuditingTool(settings, sinkTasks)
  }

  def create(
      settings: AuditSettings.Standard,
      indexCreator: IndexBasedAuditSinkServiceCreator,
      dataStreamCreator: DataStreamBasedAuditSinkServiceCreator,
      httpClientsFactory: HttpClientsFactory
  )(
      using Clock,
      LoggingContext
  ): Task[Either[NonEmptyList[CreationError], Option[AuditingTool]]] = {
    val sinkTasks = settings.auditSinks.toList.flatMap {
      case Enabled(config: AuditSink.Config.EsIndexBasedSink) =>
        Some(createIndexSink(config, indexCreator, httpClientsFactory))
      case Enabled(config: AuditSink.Config.EsDataStreamBasedSink) =>
        Some(createDataStreamSink(config, dataStreamCreator, httpClientsFactory))
      case Enabled(AuditSink.Config.LogBasedSink(serializer, loggerName)) =>
        Some(createLogSink(serializer, loggerName))
      case Disabled =>
        None
    }
    createAuditingTool(settings, sinkTasks)
  }

  private def createIndexSink(
      config: Config.EsIndexBasedSink,
      creator: IndexBasedAuditSinkServiceCreator,
      httpClientsFactory: HttpClientsFactory,
  )(
      using Clock
  ): Task[Either[CreationError, SupportedAuditSink]] = {
    (for {
      service <- EitherT(creator.createIndexService(config.auditCluster, httpClientsFactory))
        .leftMap(e => CreationError(e.message))
    } yield EsIndexBasedAuditSink(
      serializer = config.logSerializer,
      indexTemplate = config.rorAuditIndexTemplate,
      auditSinkService = service
    )).value
  }

  private def createDataStreamSink(
      config: Config.EsDataStreamBasedSink,
      creator: DataStreamBasedAuditSinkServiceCreator,
      httpClientsFactory: HttpClientsFactory,
  ): Task[Either[CreationError, SupportedAuditSink]] = {
    (for {
      service <- EitherT(creator.createDataStreamService(config.auditCluster, httpClientsFactory))
        .leftMap(e => CreationError(e.message))
      auditSink <- EitherT(
        EsDataStreamBasedAuditSink
          .create(config.logSerializer, config.rorAuditDataStream, service, config.auditCluster)
          .map(_.leftMap(error => CreationError(error.message)))
          .redeemWith(
            recover = ex => Task.delay(service.close()) >> Task.raiseError(ex),
            bind = {
              case left @ Left(_) => Task.delay(service.close()).as(left)
              case right          => Task.pure(right)
            }
          )
      )
    } yield auditSink).value
  }

  private def createLogSink(
      serializer: AuditLogSerializer,
      loggerName: RorAuditLoggerName
  ): Task[Either[CreationError, SupportedAuditSink]] = {
    Task.delay(Right(new LogBasedAuditSink(serializer, loggerName)))
  }

  private def createAuditingTool[C](
      settings: AuditSettings[C],
      sinkTasks: List[Task[Either[CreationError, SupportedAuditSink]]]
  )(
      using LoggingContext
  ): Task[Either[NonEmptyList[CreationError], Option[AuditingTool]]] = {
    NonEmptyList.fromList(sinkTasks) match {
      case Some(tasks) =>
        tasks
          .map(_.attempt)
          .parSequence
          .flatMap[Either[NonEmptyList[CreationError], NonEmptyList[SupportedAuditSink]]] { attempts =>
            val (exceptions, results) = attempts.toList.separate
            val (creationErrors, sinks) = results.separate

            (NonEmptyList.fromList(exceptions), NonEmptyList.fromList(creationErrors)) match {
              case (Some(exs), _) =>
                sinks.parTraverse(_.close().handleError(logSinkCloseError)) >> Task.raiseError(exs.head)
              case (None, Some(errors)) =>
                sinks.parTraverse(_.close().handleError(logSinkCloseError)).as(Left(errors))
              case (None, None) =>
                Task.pure(Right(NonEmptyList.fromListUnsafe(sinks)))
            }
          }
          .map {
            _.map { auditSinks =>
              implicit val auditEnvironmentContext: AuditEnvironmentContext =
                new AuditEnvironmentContextBasedOnEsNodeSettings(settings.esNodeSettings)
              noRequestIdLogger.info(s"The audit is enabled with the given outputs: [${auditSinks.toList.show}]")
              Some(new AuditingTool(auditSinks))
            }
          }
      case None =>
        noRequestIdLogger.info("The audit is disabled because no output is enabled")
        Task.pure(Right(None))
    }
  }

  private val logSinkCloseError: Throwable => Unit =
    ex => noRequestIdLogger.warn(s"Failed to close audit sink during error recovery: ${ex.getMessage}")

  private type SupportedAuditSink = EsIndexBasedAuditSink | EsDataStreamBasedAuditSink | LogBasedAuditSink

  private given Show[SupportedAuditSink] = Show.show {
    case _: EsIndexBasedAuditSink      => "index"
    case _: LogBasedAuditSink          => "log"
    case _: EsDataStreamBasedAuditSink => "data_stream"
  }

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
