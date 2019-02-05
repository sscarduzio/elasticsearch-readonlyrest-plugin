package tech.beshu.ror.acl.blocks.rules

import cats.data.NonEmptySet
import com.softwaremill.sttp.Method
import monix.eval.Task
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.MethodsRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.{RuleResult, RegularRule}
import tech.beshu.ror.acl.request.RequestContext

class MethodsRule(val settings: Settings)
  extends RegularRule {

  override val name: Rule.Name = MethodsRule.name

  /*
    NB: Elasticsearch will parse as GET any HTTP methods that it does not understand.
    So it's normal if you allowed GET and see a 'LINK' request going throw.
    It's actually interpreted by all means as a GET!
   */
  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = Task {
    RuleResult.fromCondition(blockContext) {
      settings.methods.contains(requestContext.method)
    }
  }
}

object MethodsRule {
  val name = Rule.Name("methods")
  final case class Settings(methods: NonEmptySet[Method])
}
