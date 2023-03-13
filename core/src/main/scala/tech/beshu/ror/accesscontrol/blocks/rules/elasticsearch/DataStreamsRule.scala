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
package tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch

import cats.data.NonEmptySet
import cats.implicits._
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.BlockContext.DataStreamRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.DataStreamsRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RegularRule, RuleName, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.DataStreamName
import tech.beshu.ror.accesscontrol.matchers.ZeroKnowledgeDataStreamsFilterScalaAdapter.CheckResult
import tech.beshu.ror.accesscontrol.matchers.{MatcherWithWildcardsScalaAdapter, ZeroKnowledgeDataStreamsFilterScalaAdapter}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.utils.RuntimeMultiResolvableVariableOps.resolveAll
import tech.beshu.ror.utils.ZeroKnowledgeIndexFilter

class DataStreamsRule(val settings: Settings)
  extends RegularRule
    with Logging {

  override val name: Rule.Name = DataStreamsRule.Name.name

  private val zeroKnowledgeMatchFilter = new ZeroKnowledgeDataStreamsFilterScalaAdapter(
    new ZeroKnowledgeIndexFilter(true)
  )

  override def regularCheck[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Rule.RuleResult[B]] = Task {
    BlockContextUpdater[B] match {
      case BlockContextUpdater.DataStreamRequestBlockContextUpdater =>
        checkDataStreams(blockContext)
      case _ =>
        Fulfilled(blockContext)
    }
  }

  private def checkDataStreams(blockContext: DataStreamRequestBlockContext): RuleResult[DataStreamRequestBlockContext] = {
    checkAllowedDataStreams(
      resolveAll(settings.allowedDataStreams.toNonEmptyList, blockContext).toSet,
      blockContext.dataStreams,
      blockContext.requestContext
    ) match {
      case Right(filteredDataStreams) => Fulfilled(blockContext.withDataStreams(filteredDataStreams))
      case Left(()) => Rejected()
    }
  }

  private def checkAllowedDataStreams(allowedDataStreams: Set[DataStreamName],
                                      dataStreamsToCheck: Set[DataStreamName],
                                      requestContext: RequestContext) = {
    if (allowedDataStreams.contains(DataStreamName.All) || allowedDataStreams.contains(DataStreamName.Wildcard)) {
      Right(dataStreamsToCheck)
    } else {
      zeroKnowledgeMatchFilter.check(
        dataStreamsToCheck,
        MatcherWithWildcardsScalaAdapter.create(allowedDataStreams)
      ) match {
        case CheckResult.Ok(processedDataStreams) if requestContext.isReadOnlyRequest =>
          Right(processedDataStreams)
        case CheckResult.Ok(processedDataStreams) if processedDataStreams.size === dataStreamsToCheck.size =>
          Right(processedDataStreams)
        case CheckResult.Ok(processedDataStreams) =>
          val filteredOutDataStreams = dataStreamsToCheck.diff(processedDataStreams).map(_.show)
          logger.debug(
            s"[${requestContext.id.show}] Write request with data streams cannot proceed because some of the data streams " +
              s"[${filteredOutDataStreams.toList.mkString_(",")}] were filtered out by ACL. The request will be rejected.."
          )
          Left(())
        case CheckResult.Failed =>
          logger.debug(s"[${requestContext.id.show}] The processed data streams do not match the allowed data streams. The request will be rejected..")
          Left(())
      }
    }
  }
}

object DataStreamsRule {
  implicit case object Name extends RuleName[DataStreamsRule] {
    override val name: Rule.Name = Rule.Name("data_streams")
  }

  final case class Settings(allowedDataStreams: NonEmptySet[RuntimeMultiResolvableVariable[DataStreamName]])
}
