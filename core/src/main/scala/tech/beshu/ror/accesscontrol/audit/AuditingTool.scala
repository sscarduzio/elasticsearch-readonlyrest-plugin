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
import tech.beshu.ror.accesscontrol.audit.AuditingTool.AuditOutputConfig.*
import tech.beshu.ror.accesscontrol.audit.acl.AclAuditLogSerializer
import tech.beshu.ror.accesscontrol.audit.output.*
import tech.beshu.ror.accesscontrol.blocks.Block.Audit
import tech.beshu.ror.accesscontrol.blocks.Block.Audit.Enabled.PrecomputedAuditOutputs
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.AuditCluster.*
import tech.beshu.ror.accesscontrol.logging.ResponseContext
import tech.beshu.ror.accesscontrol.logging.ResponseContext.*
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.audit.instances.BlockVerbosityAwareAuditLogSerializer
import tech.beshu.ror.audit.{AuditEnvironmentContext, AuditRequestContext, AuditResponseContext}
import tech.beshu.ror.es.EsNodeSettings
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.RequestIdAwareLogging

import java.time.Clock

final class AuditingTool private (private[ror] val outputs: List[AuditOutput])(
    implicit loggingContext: LoggingContext,
    auditEnvironmentContext: AuditEnvironmentContext
) {

  def audit[B <: BlockContext](response: ResponseContext[B]): Task[Unit] = {
    NonEmptyList.fromList(activeOutputsFor(response)) match {
      case Some(nel) =>
        val auditResponseContext = toAuditResponse(response, auditEnvironmentContext)
        implicit val requestId: RequestId = response.requestContext.id.toRequestId
        nel.parTraverse(_.submit(auditResponseContext)).map(_ => ())
      case None =>
        Task.unit
    }
  }

  private def activeOutputsFor[B <: BlockContext](responseContext: ResponseContext[B]): List[AuditOutput] = {
    responseContext match {
      case AllowedBy(_, blockContext, _) =>
        auditOutputsFor(blockContext.block)
      case Allowed(_, UserMetadata.WithoutGroups(_, _, _, metadataOrigin), _) =>
        auditOutputsFor(metadataOrigin.blockContext.block)
      case Allowed(_, UserMetadata.WithGroups(groupsMetadata), _) =>
        groupsMetadata.values.toList
          .map(_.metadataOrigin.blockContext.block)
          .flatMap(auditOutputsFor)
          .distinct
      case ForbiddenBy(_, blockContext, _) => auditOutputsFor(blockContext.block)
      case Forbidden(_, _)                 => outputs
      case RequestedIndexNotExist(_, _)    => outputs
      case Errored(_, _)                   => outputs
    }
  }

  private def auditOutputsFor(block: Block): List[AuditOutput] = block.audit match {
    case Audit.Enabled(_, _, PrecomputedAuditOutputs.Available(auditOutputs)) => auditOutputs
    case Audit.Enabled(_, _, PrecomputedAuditOutputs.NotAvailable)            => Nil
    case Audit.Disabled                                                       => Nil
  }

  def close(): Task[Unit] = outputs.traverse(_.close()).void

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

  sealed trait AuditOutputs[+O <: AuditOutputConfig]

  object AuditOutputs {
    case object Disabled extends AuditOutputs[Nothing]
    case object Defaults extends AuditOutputs[Nothing]
    final case class Configured[+O <: AuditOutputConfig](outputs: NonEmptyList[O]) extends AuditOutputs[O]
  }

  sealed trait AuditOutputConfig

  object AuditOutputConfig {
    sealed trait WithoutDataStream extends AuditOutputConfig

    final case class EsIndexBased(name: AuditOutputName, config: EsIndexBasedSettings) extends WithoutDataStream
    final case class EsDataStreamBased(name: AuditOutputName, config: EsDataStreamBasedSettings)
        extends AuditOutputConfig
    final case class LogBased(name: AuditOutputName, config: LogBasedSettings) extends WithoutDataStream
    final case class RollingFileBased(name: AuditOutputName, config: RollingFileBasedSettings) extends WithoutDataStream
    case object Disabled extends WithoutDataStream

    final case class EsIndexBasedSettings(
        serializer: JsonAuditSerializer,
        rorAuditIndexTemplate: RorAuditIndexTemplate,
        auditCluster: AuditCluster
    )

    object EsIndexBasedSettings {

      val default: EsIndexBasedSettings = EsIndexBasedSettings(
        serializer = AuditSerializer.Delegating(new BlockVerbosityAwareAuditLogSerializer),
        rorAuditIndexTemplate = RorAuditIndexTemplate.default,
        auditCluster = LocalAuditCluster,
      )

    }

    final case class EsDataStreamBasedSettings(
        serializer: JsonAuditSerializer,
        rorAuditDataStream: RorAuditDataStream,
        auditCluster: AuditCluster
    )

    object EsDataStreamBasedSettings {

      val default: EsDataStreamBasedSettings = EsDataStreamBasedSettings(
        serializer = AuditSerializer.Delegating(new BlockVerbosityAwareAuditLogSerializer),
        rorAuditDataStream = RorAuditDataStream.default,
        auditCluster = LocalAuditCluster,
      )

    }

    final case class LogBasedSettings(serializer: AuditSerializer, loggerName: RorAuditLoggerName)

    object LogBasedSettings {

      val default: LogBasedSettings = LogBasedSettings(
        serializer = AuditSerializer.Delegating(new BlockVerbosityAwareAuditLogSerializer),
        loggerName = RorAuditLoggerName.default
      )

    }

    final case class RollingFileBasedSettings(
        serializer: AuditSerializer,
        loggerName: RorAuditLoggerName,
        fileAppender: RollingFileBasedSettings.FileAppenderConfig
    )

    object RollingFileBasedSettings {
      final case class FileAppenderConfig(filePath: java.nio.file.Path, maxFileSize: Information, maxFiles: PosInt)
    }

  }

  final case class AuditingConfig[+O <: AuditOutputConfig](
      outputs: AuditOutputs[O],
      defaultAclLog: Boolean,
      esNodeSettings: EsNodeSettings
  )

  object AuditingConfig {
    type IndexOnly = AuditingConfig[AuditOutputConfig.WithoutDataStream]
    type IndexOrDataStream = AuditingConfig[AuditOutputConfig]
    type AnyOutput = AuditingConfig[AuditOutputConfig]
  }

  final case class CreationError(message: String) extends AnyVal

  def create(
      config: AuditingConfig.IndexOnly,
      creator: IndexBasedAuditOutputServiceCreator
  )(
      using Clock,
      LoggingContext
  ): Task[Either[NonEmptyList[CreationError], AuditingTool]] = {
    val effectiveOutputs: List[AuditOutputConfig.WithoutDataStream] =
      applyDefaults(config.outputs, config.defaultAclLog)
    val outputTasks = effectiveOutputs.flatMap {
      case s: EsIndexBased     => Some(createIndexOutput(s, creator))
      case s: LogBased         => Some(createLogOutput(s))
      case s: RollingFileBased => Some(createRollingFileBaseOutput(s))
      case Disabled            => None
    }
    createAuditingTool(config.esNodeSettings, outputTasks)
  }

  def create(
      config: AuditingConfig.IndexOrDataStream,
      indexCreator: IndexBasedAuditOutputServiceCreator,
      dataStreamCreator: DataStreamBasedAuditOutputServiceCreator
  )(
      using Clock,
      LoggingContext
  ): Task[Either[NonEmptyList[CreationError], AuditingTool]] = {
    val effectiveOutputs: List[AuditOutputConfig] =
      applyDefaults(config.outputs, config.defaultAclLog)
    val outputTasks = effectiveOutputs.flatMap {
      case s: EsIndexBased      => Some(createIndexOutput(s, indexCreator))
      case s: EsDataStreamBased => Some(createDataStreamOutput(s, dataStreamCreator))
      case s: LogBased          => Some(createLogOutput(s))
      case s: RollingFileBased  => Some(createRollingFileBaseOutput(s))
      case Disabled             => None
    }
    createAuditingTool(config.esNodeSettings, outputTasks)
  }

  private def applyDefaults[O >: AuditOutputConfig.WithoutDataStream <: AuditOutputConfig](
      settings: AuditOutputs[O],
      defaultAclLog: Boolean
  ): List[O] = {
    val outputs: List[O] = settings match {
      case AuditOutputs.Disabled            => List.empty
      case AuditOutputs.Defaults            => List(defaultIndexStorageOutput)
      case AuditOutputs.Configured(outputs) => outputs.toList
    }
    if (defaultAclLog) defaultAclOutput :: outputs else outputs
  }

  private def defaultAclOutput: LogBased =
    LogBased(
      AuditOutputName.defaultAclLog,
      LogBasedSettings(AuditSerializer.Acl, AclAuditLogSerializer.defaultLoggerName)
    )

  private def defaultIndexStorageOutput: EsIndexBased =
    EsIndexBased(AuditOutputName.defaultIndexStorage, EsIndexBasedSettings.default)

  private def createIndexOutput(
      output: EsIndexBased,
      creator: IndexBasedAuditOutputServiceCreator
  )(
      using Clock
  ): Task[Either[CreationError, SupportedAuditOutput]] = Task.delay {
    Right(
      EsIndexBasedAuditOutput(
        outputName = output.name,
        serializer = output.config.serializer,
        indexTemplate = output.config.rorAuditIndexTemplate,
        auditOutputService = creator.index(output.config.auditCluster)
      )
    )
  }

  private def createDataStreamOutput(
      output: EsDataStreamBased,
      creator: DataStreamBasedAuditOutputServiceCreator
  ): Task[Either[CreationError, SupportedAuditOutput]] = {
    (for {
      service <- EitherT.right[CreationError](Task.delay(creator.dataStream(output.config.auditCluster)))
      auditOutput <- EitherT(
        EsDataStreamBasedAuditOutput
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
    } yield auditOutput).value
  }

  private def createLogOutput(
      output: LogBased
  ): Task[Either[CreationError, SupportedAuditOutput]] = {
    Task.delay(Right(new LogBasedAuditOutput(output.name, output.config.serializer, output.config.loggerName)))
  }

  private def createRollingFileBaseOutput(
      output: RollingFileBased
  ): Task[Either[CreationError, SupportedAuditOutput]] = {
    RollingFileBasedAuditOutput
      .create(output.name, output.config.serializer, output.config.loggerName, output.config.fileAppender)
      .map(_.leftMap(e => CreationError(e.message)))
  }

  private def createAuditingTool(
      esNodeSettings: EsNodeSettings,
      outputTasks: List[Task[Either[CreationError, SupportedAuditOutput]]]
  )(
      using LoggingContext
  ): Task[Either[NonEmptyList[CreationError], AuditingTool]] = {
    outputTasks
      .map(_.attempt)
      .parSequence
      .flatMap[Either[NonEmptyList[CreationError], List[SupportedAuditOutput]]] { attempts =>
        val (exceptions, results) = attempts.separate
        val (creationErrors, outputs) = results.separate

        (NonEmptyList.fromList(exceptions), NonEmptyList.fromList(creationErrors)) match {
          case (Some(exs), _) =>
            Task.delay(exs.tail.foreach(logDiscardedOutputCreationError)) >>
              outputs.parTraverse(_.close().handleError(logOutputCloseError)) >> Task.raiseError(exs.head)
          case (None, Some(errors)) =>
            outputs.parTraverse(_.close().handleError(logOutputCloseError)).as(Left(errors))
          case (None, None) =>
            Task.pure(Right(outputs))
        }
      }
      .map {
        _.map { auditOutputs =>
          implicit val auditEnvironmentContext: AuditEnvironmentContext =
            new AuditEnvironmentContextBasedOnEsNodeSettings(esNodeSettings)
          if (auditOutputs.nonEmpty)
            noRequestIdLogger.info(s"The audit is enabled with the given outputs: [${auditOutputs.show}]")
          else
            noRequestIdLogger.info("The audit is disabled because no output is enabled")
          new AuditingTool(auditOutputs)
        }
      }
  }

  private val logOutputCloseError: Throwable => Unit =
    ex => noRequestIdLogger.warn(s"Failed to close audit output during error recovery: ${ex.getMessage}")

  private val logDiscardedOutputCreationError: Throwable => Unit =
    ex => noRequestIdLogger.warn(s"Another audit output also failed to be created: ${ex.getMessage}", ex)

  private type SupportedAuditOutput = EsIndexBasedAuditOutput | EsDataStreamBasedAuditOutput | LogBasedAuditOutput |
    RollingFileBasedAuditOutput

  private given showSupportedAuditOutput: Show[SupportedAuditOutput] = Show.show {
    case _: EsIndexBasedAuditOutput      => "index"
    case _: LogBasedAuditOutput          => "log"
    case _: RollingFileBasedAuditOutput  => "log_file"
    case _: EsDataStreamBasedAuditOutput => "data_stream"
  }

  private given Show[List[SupportedAuditOutput]] = outputs => outputs.map(_.show).mkString(", ")

  extension (output: AuditOutputConfig) {

    def outputName: Option[AuditOutputName] = output match {
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
