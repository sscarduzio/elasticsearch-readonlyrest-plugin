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
import eu.timepit.refined.types.numeric.PosInt
import monix.eval.Task
import org.json.JSONObject
import squants.information.Information
import tech.beshu.ror.accesscontrol.History
import tech.beshu.ror.accesscontrol.audit.AuditingTool.*
import tech.beshu.ror.accesscontrol.audit.AuditingTool.AuditOutputsConfig.AuditOutput
import tech.beshu.ror.accesscontrol.audit.AuditingTool.AuditOutputsConfig.AuditOutput.*
import tech.beshu.ror.accesscontrol.audit.acl.AclAuditLogSerializer
import tech.beshu.ror.accesscontrol.audit.sink.*
import tech.beshu.ror.accesscontrol.blocks.Block.Audit
import tech.beshu.ror.accesscontrol.blocks.Block.Audit.Enabled.PrecomputedAuditSinks
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.AuditCluster.*
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory
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

  sealed trait Mode

  object Mode {
    sealed trait Standard extends Mode
    sealed trait Legacy extends Mode
    type Both = Standard & Legacy
  }

  sealed trait AuditOutputsConfig[+M <: Mode]

  object AuditOutputsConfig {
    case object NoOutputsConfigured extends AuditOutputsConfig[Nothing]
    final case class WithOutputs[+M <: Mode](outputs: NonEmptyList[AuditOutput[M]]) extends AuditOutputsConfig[M]

    sealed trait AuditOutput[+M <: Mode]

    object AuditOutput {
      final case class EsIndexBased(name: SinkName, config: EsIndexBasedSink) extends AuditOutput[Mode.Both]
      final case class EsDataStreamBased(name: SinkName, config: EsDataStreamBasedSink)
          extends AuditOutput[Mode.Standard]
      final case class LogBased(name: SinkName, config: LogBasedSink) extends AuditOutput[Mode.Both]
      final case class RollingFileBased(name: SinkName, config: RollingFileBasedSink) extends AuditOutput[Mode.Both]
      case object Disabled extends AuditOutput[Nothing]

      final case class EsIndexBasedSink(
          serializer: JsonAuditSerializer,
          rorAuditIndexTemplate: RorAuditIndexTemplate,
          auditCluster: AuditCluster
      )

      object EsIndexBasedSink {

        val default: EsIndexBasedSink = EsIndexBasedSink(
          serializer = AuditSerializer.Delegating(new BlockVerbosityAwareAuditLogSerializer),
          rorAuditIndexTemplate = RorAuditIndexTemplate.default,
          auditCluster = LocalAuditCluster,
        )

      }

      final case class EsDataStreamBasedSink(
          serializer: JsonAuditSerializer,
          rorAuditDataStream: RorAuditDataStream,
          auditCluster: AuditCluster
      )

      object EsDataStreamBasedSink {

        val default: EsDataStreamBasedSink = EsDataStreamBasedSink(
          serializer = AuditSerializer.Delegating(new BlockVerbosityAwareAuditLogSerializer),
          rorAuditDataStream = RorAuditDataStream.default,
          auditCluster = LocalAuditCluster,
        )

      }

      final case class LogBasedSink(serializer: AuditSerializer, loggerName: RorAuditLoggerName)

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
      )

      object RollingFileBasedSink {
        final case class FileAppenderConfig(filePath: java.nio.file.Path, maxFileSize: Information, maxFiles: PosInt)
      }

    }

  }

  final case class AuditingConfig[+M <: Mode](
      outputsConfig: Option[AuditOutputsConfig[M]],
      defaultAclLog: Boolean,
      esNodeSettings: EsNodeSettings
  )

  object AuditingConfig {
    type Standard = AuditingConfig[Mode.Standard]
    type Legacy = AuditingConfig[Mode.Legacy]
    type AnyMode = AuditingConfig[Mode]
  }

  final case class CreationError(message: String) extends AnyVal

  def create(
      config: AuditingConfig.Legacy,
      creator: IndexBasedAuditSinkServiceCreator,
      httpClientsFactory: HttpClientsFactory
  )(
      using Clock,
      LoggingContext
  ): Task[Either[NonEmptyList[CreationError], AuditingTool]] = {
    val effectiveOutputs: List[AuditOutput[Mode.Legacy]] =
      applyDefaults(config.outputsConfig, config.defaultAclLog)
    val sinkTasks = effectiveOutputs.flatMap {
      case s: EsIndexBased     => Some(createIndexSink(s, creator, httpClientsFactory))
      case s: LogBased         => Some(createLogSink(s))
      case s: RollingFileBased => Some(createRollingFileBaseSink(s))
      case Disabled            => None
    }
    createAuditingTool(config.esNodeSettings, sinkTasks)
  }

  def create(
      config: AuditingConfig.Standard,
      indexCreator: IndexBasedAuditSinkServiceCreator,
      dataStreamCreator: DataStreamBasedAuditSinkServiceCreator,
      httpClientsFactory: HttpClientsFactory
  )(
      using Clock,
      LoggingContext
  ): Task[Either[NonEmptyList[CreationError], AuditingTool]] = {
    val effectiveOutputs: List[AuditOutput[Mode.Standard]] =
      applyDefaults(config.outputsConfig, config.defaultAclLog)
    val sinkTasks = effectiveOutputs.flatMap {
      case s: EsIndexBased      => Some(createIndexSink(s, indexCreator, httpClientsFactory))
      case s: EsDataStreamBased => Some(createDataStreamSink(s, dataStreamCreator, httpClientsFactory))
      case s: LogBased          => Some(createLogSink(s))
      case s: RollingFileBased  => Some(createRollingFileBaseSink(s))
      case Disabled             => None
    }
    createAuditingTool(config.esNodeSettings, sinkTasks)
  }

  private def applyDefaults[M >: Mode.Both <: Mode](
      settings: Option[AuditOutputsConfig[M]],
      defaultAclLog: Boolean
  ): List[AuditOutput[M]] = {
    val outputs: List[AuditOutput[M]] = settings match {
      case None                                          => List.empty
      case Some(AuditOutputsConfig.NoOutputsConfigured)  => List(defaultIndexStorageSink)
      case Some(AuditOutputsConfig.WithOutputs(outputs)) => outputs.toList
    }
    if (defaultAclLog) defaultAclSink :: outputs else outputs
  }

  private def defaultAclSink: LogBased =
    LogBased(SinkName.defaultAclLog, LogBasedSink(AuditSerializer.Acl, AclAuditLogSerializer.defaultLoggerName))

  private def defaultIndexStorageSink: EsIndexBased =
    EsIndexBased(SinkName.defaultIndexStorage, EsIndexBasedSink.default)

  private def createIndexSink(
      output: EsIndexBased,
      creator: IndexBasedAuditSinkServiceCreator,
      httpClientsFactory: HttpClientsFactory,
  )(
      using Clock
  ): Task[Either[CreationError, SupportedAuditSink]] = {
    (for {
      service <- EitherT(creator.createIndexService(output.config.auditCluster, httpClientsFactory))
        .leftMap(e => CreationError(e.message))
    } yield EsIndexBasedAuditSink(
      sinkName = output.name,
      serializer = output.config.serializer,
      indexTemplate = output.config.rorAuditIndexTemplate,
      auditSinkService = service
    )).value
  }

  private def createDataStreamSink(
      output: EsDataStreamBased,
      creator: DataStreamBasedAuditSinkServiceCreator,
      httpClientsFactory: HttpClientsFactory,
  ): Task[Either[CreationError, SupportedAuditSink]] = {
    (for {
      service <- EitherT(creator.createDataStreamService(output.config.auditCluster, httpClientsFactory))
        .leftMap(e => CreationError(e.message))
      auditSink <- EitherT(
        EsDataStreamBasedAuditSink
          .create(
            output.name,
            output.config.serializer,
            output.config.rorAuditDataStream,
            service,
            output.config.auditCluster
          )
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
      output: LogBased
  ): Task[Either[CreationError, SupportedAuditSink]] = {
    Task.delay(Right(new LogBasedAuditSink(output.name, output.config.serializer, output.config.loggerName)))
  }

  private def createRollingFileBaseSink(
      output: RollingFileBased
  ): Task[Either[CreationError, SupportedAuditSink]] = {
    RollingFileBasedAuditSink
      .create(output.name, output.config.serializer, output.config.loggerName, output.config.fileAppender)
      .map(_.leftMap(e => CreationError(e.message)))
  }

  private def createAuditingTool(
      esNodeSettings: EsNodeSettings,
      sinkTasks: List[Task[Either[CreationError, SupportedAuditSink]]]
  )(
      using LoggingContext
  ): Task[Either[NonEmptyList[CreationError], AuditingTool]] = {
    sinkTasks
      .map(_.attempt)
      .parSequence
      .flatMap[Either[NonEmptyList[CreationError], List[SupportedAuditSink]]] { attempts =>
        val (exceptions, results) = attempts.separate
        val (creationErrors, sinks) = results.separate

        (NonEmptyList.fromList(exceptions), NonEmptyList.fromList(creationErrors)) match {
          case (Some(exs), _) =>
            sinks.parTraverse(_.close().handleError(logSinkCloseError)) >> Task.raiseError(exs.head)
          case (None, Some(errors)) =>
            sinks.parTraverse(_.close().handleError(logSinkCloseError)).as(Left(errors))
          case (None, None) =>
            Task.pure(Right(sinks))
        }
      }
      .map {
        _.map { auditSinks =>
          implicit val auditEnvironmentContext: AuditEnvironmentContext =
            new AuditEnvironmentContextBasedOnEsNodeSettings(esNodeSettings)
          if (auditSinks.nonEmpty)
            noRequestIdLogger.info(s"The audit is enabled with the given outputs: [${auditSinks.show}]")
          else
            noRequestIdLogger.info("The audit is disabled because no output is enabled")
          new AuditingTool(auditSinks)
        }
      }
  }

  private val logSinkCloseError: Throwable => Unit =
    ex => noRequestIdLogger.warn(s"Failed to close audit sink during error recovery: ${ex.getMessage}")

  private type SupportedAuditSink = EsIndexBasedAuditSink | EsDataStreamBasedAuditSink | LogBasedAuditSink |
    RollingFileBasedAuditSink

  private given showSupportedAuditSink: Show[SupportedAuditSink] = Show.show {
    case _: EsIndexBasedAuditSink      => "index"
    case _: LogBasedAuditSink          => "log"
    case _: RollingFileBasedAuditSink  => "log_file"
    case _: EsDataStreamBasedAuditSink => "data_stream"
  }

  private given Show[List[SupportedAuditSink]] = sinks => sinks.map(_.show).mkString(", ")

  extension [M <: Mode](output: AuditOutput[M]) {

    def sinkName: Option[SinkName] = output match {
      case s: EsIndexBased      => Some(s.name)
      case s: EsDataStreamBased => Some(s.name)
      case s: LogBased          => Some(s.name)
      case s: RollingFileBased  => Some(s.name)
      case Disabled             => None
    }

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
