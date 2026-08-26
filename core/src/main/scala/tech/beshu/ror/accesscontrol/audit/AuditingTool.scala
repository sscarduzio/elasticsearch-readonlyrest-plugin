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
import tech.beshu.ror.accesscontrol.audit.AuditingTool.AuditSettings.AuditOutputConfig.{Disabled, Enabled}
import tech.beshu.ror.accesscontrol.audit.acl.AclAuditLogSerializer
import tech.beshu.ror.accesscontrol.audit.output.*
import tech.beshu.ror.accesscontrol.blocks.Block.Audit
import tech.beshu.ror.accesscontrol.blocks.Block.Audit.Enabled.PrecomputedAuditOutputs
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

  object AuditSettings {

    sealed trait AuditOutputConfig

    object AuditOutputConfig {
      final case class Enabled(name: AuditOutputName, config: AuditSettings.AuditOutputConfig.Config)
          extends AuditOutputConfig

      case object Disabled extends AuditOutputConfig

      sealed trait Config

      object Config {

        final case class EsIndexBasedOutput(
            serializer: JsonAuditSerializer,
            rorAuditIndexTemplate: RorAuditIndexTemplate,
            auditCluster: AuditCluster
        ) extends Config

        object EsIndexBasedOutput {

          val default: EsIndexBasedOutput = EsIndexBasedOutput(
            serializer = AuditSerializer.Delegating(new BlockVerbosityAwareAuditLogSerializer),
            rorAuditIndexTemplate = RorAuditIndexTemplate.default,
            auditCluster = AuditCluster.LocalAuditCluster,
          )

        }

        final case class EsDataStreamBasedOutput(
            serializer: JsonAuditSerializer,
            rorAuditDataStream: RorAuditDataStream,
            auditCluster: AuditCluster
        ) extends Config

        object EsDataStreamBasedOutput {

          val default: EsDataStreamBasedOutput = EsDataStreamBasedOutput(
            serializer = AuditSerializer.Delegating(new BlockVerbosityAwareAuditLogSerializer),
            rorAuditDataStream = RorAuditDataStream.default,
            auditCluster = AuditCluster.LocalAuditCluster,
          )

        }

        final case class LogBasedOutput(serializer: AuditSerializer, loggerName: RorAuditLoggerName) extends Config

        object LogBasedOutput {

          val default: LogBasedOutput = LogBasedOutput(
            serializer = AuditSerializer.Delegating(new BlockVerbosityAwareAuditLogSerializer),
            loggerName = RorAuditLoggerName.default
          )

        }

        final case class RollingFileBasedOutput(
            serializer: AuditSerializer,
            loggerName: RorAuditLoggerName,
            fileAppender: RollingFileBasedOutput.FileAppenderConfig
        ) extends Config

        object RollingFileBasedOutput {
          final case class FileAppenderConfig(filePath: java.nio.file.Path, maxFileSize: Information, maxFiles: PosInt)
        }

      }

    }

  }

  sealed trait AuditOutputsConfig

  object AuditOutputsConfig {
    case object NoOutputsConfigured extends AuditOutputsConfig
    final case class WithOutputs(auditOutputs: NonEmptyList[AuditSettings.AuditOutputConfig]) extends AuditOutputsConfig
  }

  final case class AuditingConfig(
      outputsConfig: Option[AuditOutputsConfig],
      defaultAclLog: Boolean,
      esNodeSettings: EsNodeSettings
  )

  final case class CreationError(message: String) extends AnyVal

  def create(config: AuditingConfig, auditOutputServiceCreator: AuditOutputServiceCreator)(
      implicit clock: Clock,
      loggingContext: LoggingContext
  ): Task[Either[NonEmptyList[CreationError], AuditingTool]] = {
    val effectiveOutputs = applyDefaults(config.outputsConfig, config.defaultAclLog)
    createAuditOutputs(effectiveOutputs, auditOutputServiceCreator).map {
      _.map { auditOutputs =>
        implicit val auditEnvironmentContext: AuditEnvironmentContext =
          new AuditEnvironmentContextBasedOnEsNodeSettings(config.esNodeSettings)
        if (auditOutputs.isEmpty) {
          noRequestIdLogger.info("The audit is disabled because no output is enabled")
        } else {
          noRequestIdLogger.info(s"The audit is enabled with the given outputs: [${auditOutputs.show}]")
        }
        new AuditingTool(auditOutputs)
      }.toEither
        .leftMap { errors =>
          errors.map(error => CreationError(error.message))
        }
    }
  }

  private def applyDefaults(
      settings: Option[AuditOutputsConfig],
      defaultAclLog: Boolean
  ): List[AuditSettings.AuditOutputConfig] = {
    val outputs = settings match {
      case None                                          => List.empty
      case Some(AuditOutputsConfig.NoOutputsConfigured)  => List(defaultIndexStorageOutput)
      case Some(AuditOutputsConfig.WithOutputs(outputs)) => outputs.toList
    }
    if (defaultAclLog) defaultAclOutput :: outputs else outputs
  }

  private def defaultAclOutput = AuditSettings.AuditOutputConfig.Enabled(
    AuditOutputName.defaultAclLog,
    AuditSettings.AuditOutputConfig.Config.LogBasedOutput(AuditSerializer.Acl, AclAuditLogSerializer.defaultLoggerName)
  )

  private def defaultIndexStorageOutput = AuditSettings.AuditOutputConfig.Enabled(
    AuditOutputName.defaultIndexStorage,
    AuditSettings.AuditOutputConfig.Config.EsIndexBasedOutput.default
  )

  private def createAuditOutputs(
      outputs: List[AuditSettings.AuditOutputConfig],
      auditOutputServiceCreator: AuditOutputServiceCreator
  )(
      using Clock
  ): Task[ValidatedNel[CreationError, List[SupportedAuditOutput]]] = {
    outputs
      .map[Task[Validated[CreationError, Option[SupportedAuditOutput]]]] {
        case Enabled(name, config: AuditSettings.AuditOutputConfig.Config.EsIndexBasedOutput) =>
          val serviceCreator: IndexBasedAuditOutputServiceCreator = auditOutputServiceCreator match {
            case creator: DataStreamAndIndexBasedAuditOutputServiceCreator => creator
            case creator: IndexBasedAuditOutputServiceCreator              => creator
          }
          createIndexOutput(name, config, serviceCreator).map(_.some.valid)
        case Enabled(name, config: AuditSettings.AuditOutputConfig.Config.EsDataStreamBasedOutput) =>
          auditOutputServiceCreator match {
            case creator: DataStreamAndIndexBasedAuditOutputServiceCreator =>
              createDataStreamOutput(name, config, creator).map(_.map(_.some))
            case _: IndexBasedAuditOutputServiceCreator =>
              // todo improvement - make this state impossible
              Task.raiseError(new IllegalStateException("Data stream audit output is not supported in this version"))
          }
        case Enabled(name, config: AuditSettings.AuditOutputConfig.Config.LogBasedOutput) =>
          Task.delay(new LogBasedAuditOutput(name, config.serializer, config.loggerName).some.valid)
        case Enabled(name, config: AuditSettings.AuditOutputConfig.Config.RollingFileBasedOutput) =>
          RollingFileBasedAuditOutput
            .create(name, config.serializer, config.loggerName, config.fileAppender)
            .map(_.map(_.some).leftMap(e => CreationError(e.message)).toValidated)
        case Disabled =>
          Task.pure(None.valid)
      }
      .sequence
      .map { outputCreationResults =>
        outputCreationResults.foldLeft[(List[CreationError], List[SupportedAuditOutput])](List.empty, List.empty) {
          case ((errorsAcc, outputsAcc), result) =>
            result match {
              case Validated.Valid(Some(auditOutput)) => (errorsAcc, outputsAcc :+ auditOutput)
              case Validated.Valid(None)              => (errorsAcc, outputsAcc)
              case Validated.Invalid(error)           => (errorsAcc :+ error, outputsAcc)
            }
        }
      }
      .map { case (errors, outputs) =>
        NonEmptyList.fromList(errors).toInvalid(outputs)
      }
  }

  private def createIndexOutput(
      name: AuditOutputName,
      config: AuditSettings.AuditOutputConfig.Config.EsIndexBasedOutput,
      serviceCreator: IndexBasedAuditOutputServiceCreator
  )(
      using Clock
  ): Task[SupportedAuditOutput] = Task.delay {
    val service = serviceCreator.index(config.auditCluster)
    EsIndexBasedAuditOutput(
      outputName = name,
      serializer = config.serializer,
      indexTemplate = config.rorAuditIndexTemplate,
      auditOutputService = service
    )
  }

  private def createDataStreamOutput(
      name: AuditOutputName,
      config: AuditSettings.AuditOutputConfig.Config.EsDataStreamBasedOutput,
      serviceCreator: DataStreamAndIndexBasedAuditOutputServiceCreator
  ): Task[Validated[CreationError, SupportedAuditOutput]] =
    Task
      .delay(serviceCreator.dataStream(config.auditCluster))
      .flatMap { auditOutputService =>
        EsDataStreamBasedAuditOutput
          .create(name, config.serializer, config.rorAuditDataStream, auditOutputService, config.auditCluster)
          .map(_.leftMap(error => CreationError(error.message)).toValidated)
      }

  private type SupportedAuditOutput = EsIndexBasedAuditOutput | EsDataStreamBasedAuditOutput | LogBasedAuditOutput |
    RollingFileBasedAuditOutput

  private given showSupportedAuditOutput: Show[SupportedAuditOutput] = Show.show {
    case _: EsIndexBasedAuditOutput      => "index"
    case _: LogBasedAuditOutput          => "log"
    case _: RollingFileBasedAuditOutput  => "log_file"
    case _: EsDataStreamBasedAuditOutput => "data_stream"
  }

  private given Show[List[SupportedAuditOutput]] = outputs => outputs.map(_.show).mkString(", ")

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
