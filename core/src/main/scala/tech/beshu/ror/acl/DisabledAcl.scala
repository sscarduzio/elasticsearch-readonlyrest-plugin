package tech.beshu.ror.acl

import monix.eval.Task
import tech.beshu.ror.acl.AclHandlingResult.Result
import tech.beshu.ror.acl.blocks.Block
import tech.beshu.ror.acl.request.RequestContext

object DisabledAcl extends Acl {
  override def handle(requestContext: RequestContext): Task[AclHandlingResult] = Task.now(passRequestResult)

  private val passRequestResult: AclHandlingResult = new AclHandlingResult {
    override val history: Vector[Block.History] = Vector.empty
    override val handlingResult: Result = Result.PassedThrough
  }
}
