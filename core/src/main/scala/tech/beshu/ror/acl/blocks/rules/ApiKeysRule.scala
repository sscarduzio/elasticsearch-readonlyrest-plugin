package tech.beshu.ror.acl.blocks.rules

import cats.data.NonEmptySet
import cats.implicits._
import monix.eval.Task
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.ApiKeysRule.{Settings, xApiKeyHeaderName}
import tech.beshu.ror.acl.blocks.rules.Rule.{RuleResult, RegularRule}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.aDomain.{ApiKey, Header}
import tech.beshu.ror.acl.aDomain.Header.Name._

class ApiKeysRule(val settings: Settings)
  extends RegularRule {

  override val name: Rule.Name = ApiKeysRule.name

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = Task {
    RuleResult.fromCondition(blockContext) {
      requestContext
        .headers
        .find(_.name === xApiKeyHeaderName)
        .exists { header => settings.apiKeys.contains(ApiKey(header.value)) }
    }
  }
}

object ApiKeysRule {

  val name = Rule.Name("api_keys")

  final case class Settings(apiKeys: NonEmptySet[ApiKey])

  val xApiKeyHeaderName = Header.Name("X-Api-Key")
}