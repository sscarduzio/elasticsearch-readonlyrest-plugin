package tech.beshu.ror.acl.blocks.rules

import monix.eval.Task
import tech.beshu.ror.requestcontext.RequestContext

trait Rule {

  def `match`(context: RequestContext): Task[Boolean]
}
