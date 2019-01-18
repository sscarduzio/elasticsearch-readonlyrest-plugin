package tech.beshu.ror.acl.blocks.rules

import cats.implicits._
import cats.data.NonEmptySet
import monix.eval.Task
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.FieldsRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.{RuleResult, RegularRule}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.aDomain.DocumentField.{ADocumentField, NegatedDocumentField}
import tech.beshu.ror.acl.aDomain.Header.Name
import tech.beshu.ror.acl.aDomain.{DocumentField, Header}
import tech.beshu.ror.acl.show.logs._

class FieldsRule(val settings: Settings)
  extends RegularRule {

  override val name: Rule.Name = FieldsRule.name

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = Task.now {
    if(!requestContext.isReadOnlyRequest) RuleResult.Rejected
    else RuleResult.Fulfilled(blockContext.addContextHeader(transientFieldsHeader))
  }

  private val transientFieldsHeader = new Header(Name.transientFields, settings.fields.map(_.show).mkString(","))
}

object FieldsRule {
  val name = Rule.Name("fields")

  final case class Settings private(fields: Set[DocumentField])
  object Settings {
    def ofFields(fields: NonEmptySet[ADocumentField], notAll: Option[NegatedDocumentField]): Settings =
      Settings(fields.toSortedSet ++ notAll.toSet)
    def ofNegatedFields(fields: NonEmptySet[NegatedDocumentField], notAll: Option[NegatedDocumentField]): Settings =
      Settings(fields.toSortedSet ++ notAll.toSet)
  }
}
