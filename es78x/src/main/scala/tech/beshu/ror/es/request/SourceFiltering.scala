package tech.beshu.ror.es.request

import cats.data.NonEmptySet
import cats.implicits._
import eu.timepit.refined.types.string.NonEmptyString
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.search.fetch.subphase.FetchSourceContext
import tech.beshu.ror.accesscontrol.domain.DocumentField
import tech.beshu.ror.accesscontrol.domain.DocumentField.{ADocumentField, NegatedDocumentField}
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.es.request.SourceFiltering.SourceFilteringResult.{Applied, ClientFilteringNotApplied}
import tech.beshu.ror.fls.EnhancedFieldsPolicy

import scala.collection.SortedSet

object SourceFiltering extends Logging {

  sealed trait RuleAndClientFilteringMergeResult
  object RuleAndClientFilteringMergeResult {
    final case class Merged(including: Array[String]) extends RuleAndClientFilteringMergeResult
    case object CouldNotHandleRequestByClientIncluding extends RuleAndClientFilteringMergeResult
    case object FilteringDoNotHaveCommonItems extends RuleAndClientFilteringMergeResult
  }

  sealed trait SourceFilteringResult {
    def modifiedContext: FetchSourceContext
  }

  object SourceFilteringResult {
    final case class Applied(modifiedContext: FetchSourceContext) extends SourceFilteringResult
    final case class ClientFilteringNotApplied(modifiedContext: FetchSourceContext,
                                               ignoredClientFiltering: Array[String]) extends SourceFilteringResult
  }

  implicit class Ops(val originalContext: FetchSourceContext) extends AnyVal {

    def applyNewFields(fields: Option[NonEmptySet[DocumentField]]): SourceFilteringResult = {
      fields match {
        case Some(definedFields) =>
          val (ruleExcluding, ruleIncluding) = splitFields(definedFields)
          Option(originalContext) match {
            case Some(definedOriginalContext) =>
              if (definedOriginalContext.fetchSource()) {
                val finalExcluding = definedOriginalContext.excludes() ++ ruleExcluding
                val requestedIncluding = definedOriginalContext.includes()

                (requestedIncluding.nonEmpty, ruleIncluding.nonEmpty) match {
                  case (true, true) =>
                    mergeRuleAndClientFiltering(ruleIncluding, requestedIncluding) match {
                      case RuleAndClientFilteringMergeResult.Merged(newIncluding) =>
                        Applied(new FetchSourceContext(true, newIncluding, finalExcluding))
                      case RuleAndClientFilteringMergeResult.FilteringDoNotHaveCommonItems =>
                        Applied(new FetchSourceContext(true, Array(""), finalExcluding))
                      case RuleAndClientFilteringMergeResult.CouldNotHandleRequestByClientIncluding =>
                        ClientFilteringNotApplied(new FetchSourceContext(true, ruleIncluding.toArray, finalExcluding), requestedIncluding)
                    }
                  case (true, false) =>
                    Applied(new FetchSourceContext(true, requestedIncluding, finalExcluding))
                  case (false, true) =>
                    Applied(new FetchSourceContext(true, ruleIncluding.toArray, finalExcluding))
                  case (false, false) =>
                    Applied(new FetchSourceContext(true, Array.empty[String], finalExcluding))
                }
              } else {
                Applied(definedOriginalContext)
              }
            case None =>
              val newSourceContext = new FetchSourceContext(true, ruleIncluding.toArray, ruleExcluding.toArray)
              Applied(newSourceContext)
          }
        case None =>
          logger.debug(s"No fields defined.")
          Applied(originalContext)
      }
    }

    private def splitFields(fields: NonEmptySet[DocumentField]) = {
      fields.toNonEmptyList.toList.partitionEither {
        case d: ADocumentField => Right(d.value.value)
        case d: NegatedDocumentField => Left(d.value.value)
      }
    }
  }

  private def mergeRuleAndClientFiltering(ruleIncluding: List[String], requestedByUserIncluding: Array[String]): RuleAndClientFilteringMergeResult = {
    if (hasWildcard(requestedByUserIncluding)) {
      RuleAndClientFilteringMergeResult.CouldNotHandleRequestByClientIncluding
    } else {
      val set = ruleIncluding.map(s => ADocumentField(NonEmptyString.unsafeFrom(s))).toSet
      val nes = NonEmptySet.fromSet[DocumentField](SortedSet.empty[DocumentField] ++ set).get
      val policy = new EnhancedFieldsPolicy(nes)
      val commonIncluding = requestedByUserIncluding
        .filter { requested =>
          policy.canKeep(requested)
        }
      if (commonIncluding.nonEmpty) {
        RuleAndClientFilteringMergeResult.Merged(commonIncluding)
      } else {
        RuleAndClientFilteringMergeResult.FilteringDoNotHaveCommonItems
      }
    }
  }

  private def hasWildcard(requestedIncluding: Array[String]) = {
    requestedIncluding.exists(_.contains("*"))
  }
}
