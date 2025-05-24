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
import org.apache.logging.log4j.scala.Logging
import org.json.JSONObject
import tech.beshu.ror.accesscontrol.audit.AuditingTool.Settings.AuditSink
import tech.beshu.ror.accesscontrol.audit.AuditingTool.Settings.AuditSink.{Disabled, Enabled}
import tech.beshu.ror.accesscontrol.audit.sink.*
import tech.beshu.ror.accesscontrol.blocks.Block.{History, Verbosity}
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext}
import tech.beshu.ror.accesscontrol.domain.{AuditCluster, RorAuditDataStream, RorAuditIndexTemplate, RorAuditLoggerName}
import tech.beshu.ror.accesscontrol.logging.ResponseContext
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.audit.enrichers.AuditLogSerializerEnrichedWithEsNodeDetails
import tech.beshu.ror.audit.instances.DefaultAuditLogSerializer
import tech.beshu.ror.audit.{AuditLogSerializer, AuditRequestContext, AuditResponseContext}
import tech.beshu.ror.configuration.EsNodeConfig
import tech.beshu.ror.implicits.*

import java.time.Clock

final class AuditingTool private(auditSinks: NonEmptyList[BaseAuditSink])
                                (implicit loggingContext: LoggingContext) {

  def audit[B <: BlockContext](response: ResponseContext[B]): Task[Unit] = {
    val auditResponseContext = toAuditResponse(response)
    auditSinks
      .parTraverse(_.submit(auditResponseContext))
      .map((_: NonEmptyList[Unit]) => ())
  }

  def close(): Task[Unit] = {
    auditSinks
      .parTraverse(_.close())
      .map((_: NonEmptyList[Unit]) => ())
  }

  private def toAuditResponse[B <: BlockContext](responseContext: ResponseContext[B]): AuditResponseContext = {
    responseContext match {
      case allowedBy: ResponseContext.AllowedBy[B] =>
        AuditResponseContext.Allowed(
          requestContext = toAuditRequestContext(
            requestContext = allowedBy.requestContext,
            blockContext = Some(allowedBy.blockContext),
            userMetadata = Some(allowedBy.blockContext.userMetadata),
            historyEntries = allowedBy.history,
            generalAuditEvents = allowedBy.requestContext.generalAuditEvents
          ),
          verbosity = toAuditVerbosity(allowedBy.block.verbosity),
          reason = allowedBy.block.show
        )
      case allow: ResponseContext.Allow[B] =>
        AuditResponseContext.Allowed(
          requestContext = toAuditRequestContext(
            requestContext = allow.requestContext,
            blockContext = None,
            userMetadata = Some(allow.userMetadata),
            historyEntries = allow.history,
            generalAuditEvents = allow.requestContext.generalAuditEvents
          ),
          verbosity = toAuditVerbosity(Block.Verbosity.Info),
          reason = allow.block.show
        )
      case forbiddenBy: ResponseContext.ForbiddenBy[B] =>
        AuditResponseContext.ForbiddenBy(
          requestContext = toAuditRequestContext(
            requestContext = forbiddenBy.requestContext,
            blockContext = Some(forbiddenBy.blockContext),
            userMetadata = Some(forbiddenBy.blockContext.userMetadata),
            historyEntries = forbiddenBy.history),
          verbosity = toAuditVerbosity(forbiddenBy.block.verbosity),
          reason = forbiddenBy.block.show
        )
      case forbidden: ResponseContext.Forbidden[B] =>
        AuditResponseContext.Forbidden(toAuditRequestContext(
          requestContext = forbidden.requestContext,
          blockContext = None,
          userMetadata = None,
          historyEntries = forbidden.history))
      case requestedIndexNotExist: ResponseContext.RequestedIndexNotExist[B] =>
        AuditResponseContext.RequestedIndexNotExist(
          toAuditRequestContext(
            requestContext = requestedIndexNotExist.requestContext,
            blockContext = None,
            userMetadata = None,
            historyEntries = requestedIndexNotExist.history)
        )
      case errored: ResponseContext.Errored[B] =>
        AuditResponseContext.Errored(
          requestContext = toAuditRequestContext(
            requestContext = errored.requestContext,
            blockContext = None,
            userMetadata = None,
            historyEntries = Vector.empty),
          cause = errored.cause)
    }
  }

  private def toAuditVerbosity(verbosity: Verbosity) = verbosity match {
    case Verbosity.Info => AuditResponseContext.Verbosity.Info
    case Verbosity.Error => AuditResponseContext.Verbosity.Error
  }

  private def toAuditRequestContext[B <: BlockContext](requestContext: RequestContext.Aux[B],
                                                       blockContext: Option[B],
                                                       userMetadata: Option[UserMetadata],
                                                       historyEntries: Vector[History[B]],
                                                       generalAuditEvents: JSONObject = new JSONObject()): AuditRequestContext = {
    new AuditRequestContextBasedOnAclResult(
      requestContext,
      userMetadata,
      historyEntries,
      loggingContext,
      generalAuditEvents,
      involvesIndices(blockContext)
    )
  }

  private def involvesIndices[B <: BlockContext](blockContext: Option[B]) =
    blockContext.exists(_.involvesIndices)

}

object AuditingTool extends Logging {

  final case class Settings(auditSinks: NonEmptyList[Settings.AuditSink])

  object Settings {

    sealed trait AuditSink

    object AuditSink {
      final case class Enabled(config: AuditSink.Config) extends AuditSink

      case object Disabled extends AuditSink

      sealed trait Config

      object Config {
        final case class EsIndexBasedSink(logSerializer: AuditLogSerializer,
                                          rorAuditIndexTemplate: RorAuditIndexTemplate,
                                          auditCluster: AuditCluster,
                                          options: EsIndexBasedSink.Options) extends Config

        object EsIndexBasedSink {
          val default: EsIndexBasedSink = EsIndexBasedSink(
            logSerializer = new DefaultAuditLogSerializer,
            rorAuditIndexTemplate = RorAuditIndexTemplate.default,
            auditCluster = AuditCluster.LocalAuditCluster,
            options = Options.default,
          )

          final case class Options(enableReportingEsNodeDetails: Boolean)

          object Options {
            val default: Options = Options(
              enableReportingEsNodeDetails = true,
            )
          }

        }

        final case class EsDataStreamBasedSink(logSerializer: AuditLogSerializer,
                                               rorAuditDataStream: RorAuditDataStream,
                                               auditCluster: AuditCluster,
                                               options: EsDataStreamBasedSink.Options) extends Config

        object EsDataStreamBasedSink {
          val default: EsDataStreamBasedSink = EsDataStreamBasedSink(
            logSerializer = new DefaultAuditLogSerializer,
            rorAuditDataStream = RorAuditDataStream.default,
            auditCluster = AuditCluster.LocalAuditCluster,
            options = Options.default,
          )

          final case class Options(enableReportingEsNodeDetails: Boolean)

          object Options {
            val default: Options = Options(
              enableReportingEsNodeDetails = true,
            )
          }
        }

        final case class LogBasedSink(logSerializer: AuditLogSerializer,
                                      loggerName: RorAuditLoggerName) extends Config

        object LogBasedSink {
          val default: LogBasedSink = LogBasedSink(
            logSerializer = new DefaultAuditLogSerializer,
            loggerName = RorAuditLoggerName.default
          )
        }
      }
    }
  }

  final case class CreationError(message: String) extends AnyVal

  def create(settings: Settings,
             esNodeConfig: EsNodeConfig,
             auditSinkServiceCreator: AuditSinkServiceCreator)
            (implicit clock: Clock,
             loggingContext: LoggingContext): Task[Either[NonEmptyList[CreationError], Option[AuditingTool]]] = {
    createAuditSinks(settings, esNodeConfig, auditSinkServiceCreator).map {
      _.map {
          case Some(auditSinks) =>
            logger.info(s"The audit is enabled with the given outputs: [${auditSinks.toList.show}]")
            Some(new AuditingTool(auditSinks))
          case None =>
            logger.info("The audit is disabled because no output is enabled")
            None
        }
        .toEither
        .leftMap { errors =>
          errors.map(error => CreationError(error.message))
        }
    }
  }

  private def createAuditSinks(settings: Settings,
                               esNodeConfig: EsNodeConfig,
                               auditSinkServiceCreator: AuditSinkServiceCreator)
                              (using Clock): Task[ValidatedNel[CreationError, Option[NonEmptyList[SupportedAuditSink]]]] = {
    settings
      .auditSinks
      .toList
      .map[Task[Validated[CreationError, Option[SupportedAuditSink]]]] {
        case Enabled(config: AuditSink.Config.EsIndexBasedSink) =>
          val serviceCreator: IndexBasedAuditSinkServiceCreator = auditSinkServiceCreator match {
            case creator: DataStreamAndIndexBasedAuditSinkServiceCreator => creator
            case creator: IndexBasedAuditSinkServiceCreator => creator
          }
          createIndexSink(config, serviceCreator, esNodeConfig).map(_.some.valid)
        case Enabled(config: AuditSink.Config.EsDataStreamBasedSink) =>
          auditSinkServiceCreator match {
            case creator: DataStreamAndIndexBasedAuditSinkServiceCreator =>
              createDataStreamSink(config, creator, esNodeConfig).map(_.map(_.some))
            case _: IndexBasedAuditSinkServiceCreator =>
              // todo improvement - make this state impossible
              Task.raiseError(new IllegalStateException("Data stream audit sink is not supported in this version"))
          }
        case Enabled(AuditSink.Config.LogBasedSink(serializer, loggerName)) =>
          Task.delay(new LogBasedAuditSink(serializer, loggerName).some.valid)
        case Disabled =>
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
        NonEmptyList.fromList(errors).toInvalid(NonEmptyList.fromList(sinks))
      }
  }

  private def createIndexSink(config: AuditSink.Config.EsIndexBasedSink,
                              serviceCreator: IndexBasedAuditSinkServiceCreator,
                              esNodeConfig: EsNodeConfig)(using Clock): Task[SupportedAuditSink] = Task.delay {
    val service = serviceCreator.index(config.auditCluster)
    EsIndexBasedAuditSink(
      serializer = decorateSerializer(config.logSerializer, config.options.enableReportingEsNodeDetails, esNodeConfig),
      indexTemplate = config.rorAuditIndexTemplate,
      auditSinkService = service
    )
  }

  private def createDataStreamSink(config: AuditSink.Config.EsDataStreamBasedSink,
                                   serviceCreator: DataStreamAndIndexBasedAuditSinkServiceCreator,
                                   esNodeConfig: EsNodeConfig): Task[Validated[CreationError, SupportedAuditSink]] =
    Task.delay(serviceCreator.dataStream(config.auditCluster))
      .flatMap { auditSinkService =>
        EsDataStreamBasedAuditSink.create(
          decorateSerializer(config.logSerializer, config.options.enableReportingEsNodeDetails, esNodeConfig),
          config.rorAuditDataStream,
          auditSinkService,
          config.auditCluster
        ).map(_.leftMap(error => CreationError(error.message)).toValidated)
      }

  private def decorateSerializer(logSerializer: AuditLogSerializer,
                                 enableReportingEsNodeDetails: Boolean,
                                 esNodeConfig: EsNodeConfig) = {
    if (enableReportingEsNodeDetails) {
      new AuditLogSerializerEnrichedWithEsNodeDetails(
        underlying = logSerializer,
        clusterName = esNodeConfig.clusterName,
        nodeName = esNodeConfig.nodeName
      )
    } else {
      logSerializer
    }
  }

  private type SupportedAuditSink = EsIndexBasedAuditSink | EsDataStreamBasedAuditSink | LogBasedAuditSink

  private given Show[SupportedAuditSink] = Show.show {
    case _: EsIndexBasedAuditSink => "index"
    case _: LogBasedAuditSink => "log"
    case _: EsDataStreamBasedAuditSink => "data_stream"
  }
}
