package tech.beshu.ror.acl.blocks.rules

import cats.data.NonEmptySet
import monix.eval.Task
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.HeadersOrRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.{RuleResult, RegularRule}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.commons.aDomain.Header
import tech.beshu.ror.commons.utils.MatcherWithWildcards
import tech.beshu.ror.commons.header.FlatHeader._

import scala.collection.JavaConverters._

/**
  * We match headers in a way that the header name is case insensitive, and the header value is case sensitive
  **/
class HeadersOrRule(settings: Settings)
  extends RegularRule {

  override val name: Rule.Name = HeadersOrRule.name

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = Task.now {
    val headersSubset = requestContext
      .headers
      .filter(h => settings.headers.exists(_.name.value.toLowerCase() == h.name.value.toLowerCase()))
    if (headersSubset.isEmpty)
      RuleResult.Rejected
    else {
      RuleResult.fromCondition(blockContext) {
        new MatcherWithWildcards(settings.headers.toSortedSet.map(_.flatten).asJava)
          .filter(headersSubset.map(_.flatten).asJava)
          .size > 0
      }
    }
  }
}

object HeadersOrRule {
  val name = Rule.Name("headers_or")

  final case class Settings(headers: NonEmptySet[Header])

}
