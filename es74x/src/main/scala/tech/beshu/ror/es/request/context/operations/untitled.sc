import test.RuleResult.Fulfilled
import test.RuleResult2.Fulfilled2
import test.RuleResult3.Fulfilled3
// todo: to remove

object test {

  sealed trait Operation
  abstract class NonIndexOperation extends Operation
  sealed trait AnIndexOperation extends Operation
  sealed trait DirectIndexOperation extends AnIndexOperation
  sealed trait IndirectIndexOperation extends AnIndexOperation
  abstract class GeneralIndexOperation(val indices: Set[String]) extends DirectIndexOperation
  abstract class SqlOperation(val tables: List[String]) extends DirectIndexOperation
  sealed trait TemplateOperation extends IndirectIndexOperation
  object TemplateOperation {
    abstract class Get(val templates: List[String]) extends TemplateOperation
    abstract class Create(val template: String) extends TemplateOperation
    abstract class Delete(val template: String) extends TemplateOperation
  }

  sealed trait RequestContext[O <: Operation] {
    type BC <: BlockContext[O, _]
    def emptyBlockContext: BC
  }

  sealed trait BlockContext[O <: Operation, B <: BlockContext[O, B]] {
    def requestContext: RequestContext[O]
    def setLogged(value: String): B
  }
  trait BlockContextFotNonIndexOperation[O <: NonIndexOperation, B <: BlockContextFotNonIndexOperation[O, B]]
    extends BlockContext[O, B] {
  }
  trait BlockContextForDirectIndexOperation[O <: GeneralIndexOperation, B <: BlockContextForDirectIndexOperation[O, B]]
    extends BlockContext[O, B] {
    def filteredIndices(indices: List[String]): B
  }

  final case class GetIndexOperation(override val indices: Set[String]) extends GeneralIndexOperation(indices)
  final case class GetIndexRequestContext() extends RequestContext[GetIndexOperation] {
    type BC = GetIndexBlockContext
    override def emptyBlockContext: BC = GetIndexBlockContext(this)
  }
  final case class GetIndexBlockContext(override val requestContext: RequestContext[GetIndexOperation])
    extends BlockContextForDirectIndexOperation[GetIndexOperation, GetIndexBlockContext] {

    override def setLogged(value: String): GetIndexBlockContext = ???
    override def filteredIndices(indices: List[String]): GetIndexBlockContext = ???

  }

  val requestContext: RequestContext[GetIndexOperation] = GetIndexRequestContext()
  val blockContext = new GetIndexBlockContext(null)

  def ruleCheck[O <: Operation, BC <: BlockContext[O, BC]](blockContext: BC) = {
    blockContext match {
      case b: BlockContextForDirectIndexOperation[O, BC] =>
        b.filteredIndices(Nil)
      case b: BlockContextFotNonIndexOperation[O, BC] =>
        b.setLogged(null)
    }
  }

  sealed trait RuleResult[B <: BlockContext[O, B], O <: Operation]
  object RuleResult {
    final case class Fulfilled[B <: BlockContext[O, B], O <: Operation](blockContext: BlockContext[O, B])
      extends RuleResult[B, O]
  }

  val fullfiled: RuleResult[GetIndexBlockContext, GetIndexOperation] = Fulfilled(blockContext)


  sealed trait RuleResult2[O <: Operation, B <: BlockContext[O, B]]
  object RuleResult2 {
    final case class Fulfilled2[O <: Operation, B <: BlockContext[O, B]](blockContext: B)
      extends RuleResult[O, B]
    object Fulfilled2 {
      def create[O <: Operation, B <: BlockContext[O, B]](block: B): Fulfilled2[O, B] = Fulfilled2(block)
    }
  }

  val fullfiled2: RuleResult[Nothing, GetIndexBlockContext] = Fulfilled2(blockContext)


  sealed trait RuleResult3[O <: Operation, B <: BlockContext[O, _]]
  object RuleResult3 {
    final case class Fulfilled3[O <: Operation, B <: BlockContext[O, _]](blockContext: B)
      extends RuleResult3[O, B]
    object Fulfilled3 {
      def create[O <: Operation, B <: BlockContext[O, _]](block: B): Fulfilled3[O, B] = Fulfilled3(block)
    }
  }

  val fullfiled3 = Fulfilled3.create(blockContext)

  sealed trait O1
  trait O1I extends O1
  sealed trait B1[O <: O1]
  final case class B1I() extends B1[O1I]

  sealed trait Test[B <: B1[O], O <: O1]
  final case class T1[B <: B1[O], O <: O1](b: B) extends Test[O, B]

  val b = B1I()
  val t = T1(b)
}