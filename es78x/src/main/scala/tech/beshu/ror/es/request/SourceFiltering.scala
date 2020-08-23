package tech.beshu.ror.es.request

import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.search.fetch.subphase.FetchSourceContext
import tech.beshu.ror.accesscontrol.domain.FieldsRestrictions

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

    def applyNewFields(fields: Option[FieldsRestrictions]): SourceFilteringResult = {
      null
    }

  }
}
