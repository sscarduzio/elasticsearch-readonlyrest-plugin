package tech.beshu.ror.acl.blocks.rules

import cats.data.NonEmptySet
import com.softwaremill.sttp.Method
import monix.eval.Task
import tech.beshu.ror.acl.blocks.rules.MethodsRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.RegularRule
import tech.beshu.ror.acl.requestcontext.RequestContext

class MethodsRule(settings: Settings)
  extends RegularRule {

  /*
    NB: Elasticsearch will parse as GET any HTTP methods that it does not understand.
    So it's normal if you allowed GET and see a 'LINK' request going throw.
    It's actually interpreted by all means as a GET!
   */
  override def `match`(context: RequestContext): Task[Boolean] = Task.now {
    settings.methods.contains(context.getMethod)
  }
}

object MethodsRule {
  final case class Settings(methods: NonEmptySet[Method])
}
