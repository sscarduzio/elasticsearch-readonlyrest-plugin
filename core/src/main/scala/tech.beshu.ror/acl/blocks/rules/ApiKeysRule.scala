package tech.beshu.ror.acl.blocks.rules

import cats.data.NonEmptySet
import cats.implicits._
import monix.eval.Task
import tech.beshu.ror.acl.blocks.rules.ApiKeysRule.{Settings, xApiKeyHeaderName}
import tech.beshu.ror.acl.blocks.rules.Rule.RegularRule
import tech.beshu.ror.acl.requestcontext.RequestContext
import tech.beshu.ror.commons.aDomain.{ApiKey, Header}
import tech.beshu.ror.commons.aDomain.Header.Name._

class ApiKeysRule(settings: Settings)
  extends RegularRule {

  override def `match`(context: RequestContext): Task[Boolean] = Task.now {
    context
      .headers
      .find(_.name === xApiKeyHeaderName)
      .exists { header => settings.apiKeys.contains(ApiKey(header.value)) }
  }
}

object ApiKeysRule {

  final case class Settings(apiKeys: NonEmptySet[ApiKey])

  val xApiKeyHeaderName = Header.Name("X-Api-Key")
}