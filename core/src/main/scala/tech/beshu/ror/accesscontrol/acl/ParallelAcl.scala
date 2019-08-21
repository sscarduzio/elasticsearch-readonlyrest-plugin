//package tech.beshu.ror.accesscontrol.acl
//
//import cats.data.NonEmptyList
//import cats.implicits._
//import monix.eval.Task
//import tech.beshu.ror.accesscontrol.AccessControl
//import tech.beshu.ror.accesscontrol.blocks.Block
//import tech.beshu.ror.accesscontrol.blocks.Block.ExecutionResult.{Matched, Mismatched}
//import tech.beshu.ror.accesscontrol.blocks.Block.Policy
//import tech.beshu.ror.accesscontrol.request.RequestContext
//
//class ParallelAcl(val blocks: NonEmptyList[Block])
//  extends AccessControl {
//
//  override def handle(context: RequestContext): Task[RegularRequestResult] = {
//    Task
//      .gather(blocks.toList.map(blockExecute(_, context)))
//      .map(_.flatten)
//      .map { blockResults =>
//        if(isMatched(blockResults)) {}
//        blockResults.map {
//          case (Matched(block, blockContext), _) if block.policy === Policy.Allow =>
//          case (Matched(block, blockContext), _) =>
//          case (Mismatched(blockContext), _) =>
//
//        }
//      }
//    ???
//  }
//
//  private def blockExecute(block: Block, context: RequestContext) = {
//    block
//      .execute(context)
//      .map(Some.apply)
//      .onErrorRecover { case _ => None }
//  }
//
//  private def isMatched(blockResults: List[Block.ExecutionResult]): Boolean = {
//    blockResults
//      .collect { case r@Matched(block, _) if block.policy === Policy.Allow => r}
//      .nonEmpty
//  }
//}
