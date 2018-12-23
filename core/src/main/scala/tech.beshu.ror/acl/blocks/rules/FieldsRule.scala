package tech.beshu.ror.acl.blocks.rules

import cats.data.NonEmptySet
import monix.eval.Task
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.FieldsRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.{RuleResult, RegularRule}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.commons.aDomain.DocumentField.{ADocumentField, NegatedDocumentField}
import tech.beshu.ror.commons.aDomain.Header.Name
import tech.beshu.ror.commons.aDomain.{DocumentField, Header}

class FieldsRule(settings: Settings)
  extends RegularRule {

  override val name: Rule.Name = Rule.Name("fields")

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = Task.now {
    if(!requestContext.isReadOnlyRequest) RuleResult.Rejected
    else RuleResult.Fulfilled(blockContext.addContextHeader(transientFieldsHeader))
  }

  private val transientFieldsHeader = new Header(Name.transientFields, settings.fields.map(_.value).mkString(","))
}

object FieldsRule {
  final case class Settings private(fields: Set[DocumentField])
  object Settings {
    def ofFields(fields: NonEmptySet[ADocumentField]): Settings = Settings(fields.toSortedSet.toSet)
    def ofNegatedFields(fields: NonEmptySet[NegatedDocumentField]): Settings = Settings(fields.toSortedSet.toSet)
  }
}
