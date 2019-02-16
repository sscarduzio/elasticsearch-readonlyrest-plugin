package tech.beshu.ror.acl.blocks.rules

import cats.implicits._
import cats.data.NonEmptySet
import monix.eval.Task
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.HeadersAndRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.{RuleResult, RegularRule}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.aDomain.Header
import tech.beshu.ror.utils.MatcherWithWildcards
import tech.beshu.ror.acl.header.FlatHeader._

import scala.collection.JavaConverters._

/**
  * We match headers in a way that the header name is case insensitive, and the header value is case sensitive
  **/
class HeadersAndRule(val settings: Settings)
  extends RegularRule {

  override val name: Rule.Name = HeadersAndRule.name

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = Task {
    val headersSubset = requestContext
      .headers
      .filter(h => settings.headers.exists(_.name === h.name))
    if (headersSubset.size != settings.headers.length)
      RuleResult.Rejected
    else {
      RuleResult.fromCondition(blockContext) {
        new MatcherWithWildcards(settings.headers.toSortedSet.map(_.flatten).asJava)
          .filter(headersSubset.map(_.flatten).asJava)
          .size == settings.headers.length
      }
    }
  }
}

object HeadersAndRule {
  val name = Rule.Name("headers_and")
  val deprecatedName = Rule.Name("headers")

  // todo: clarify "should reject duplicated headers" feature (high prio)
  final case class Settings(headers: NonEmptySet[Header])

}
