package tech.beshu.ror.acl.blocks.rules.impersonation

import monix.eval.Task
import tech.beshu.ror.acl.blocks.rules.impersonation.ImpersonationSupport.UserExistence
import tech.beshu.ror.acl.domain.User

trait ImpersonationSupport {

  def exists(user: User.Id): Task[UserExistence]
}

object ImpersonationSupport {
  sealed trait UserExistence
  object UserExistence {
    case object Exists extends UserExistence
    case object NotExist extends UserExistence
    case object CannotCheck extends UserExistence
  }
}
