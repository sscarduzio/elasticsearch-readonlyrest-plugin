package tech.beshu.ror.acl.blocks.rules.impersonation

import monix.eval.Task
import tech.beshu.ror.acl.domain.User

trait ImpersonationSupport {

  def exists(user: User.Id): Task[Boolean]
}
