package tech.beshu.ror.acl.blocks.rules

import cats.data.NonEmptySet
import monix.eval.Task
import tech.beshu.ror.acl.blocks.rules.HeadersAndRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.RegularRule
import tech.beshu.ror.acl.requestcontext.RequestContext
import tech.beshu.ror.commons.aDomain.Header
import tech.beshu.ror.commons.utils.MatcherWithWildcards
import tech.beshu.ror.commons.header.FlatHeader._

import scala.collection.JavaConverters._

/**
  * We match headers in a way that the header name is case insensitive, and the header value is case sensitive
  **/
class HeadersAndRule(settings: Settings)
  extends RegularRule {

  override def `match`(context: RequestContext): Task[Boolean] = Task.now {
    val headersSubset = context
      .headers
      .filter(h => settings.headers.exists(_.name.toLowerCase() == h.name.toLowerCase()))
    if (headersSubset.size != settings.headers.length) false
    else new MatcherWithWildcards(settings.headers.toSortedSet.map(_.flatten).asJava)
      .filter(headersSubset.map(_.flatten).asJava)
      .size == settings.headers.length
  }
}

object HeadersAndRule {

  // todo: clarify "should reject duplicated headers" feature
  final case class Settings(headers: NonEmptySet[Header])

}
