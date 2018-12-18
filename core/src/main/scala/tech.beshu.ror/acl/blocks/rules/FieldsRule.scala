package tech.beshu.ror.acl.blocks.rules

import cats.data.NonEmptySet
import monix.eval.Task
import tech.beshu.ror.acl.blocks.rules.FieldsRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.RegularRule
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.commons.aDomain.DocumentField.{ADocumentField, NegatedDocumentField}
import tech.beshu.ror.commons.aDomain.Header.Name
import tech.beshu.ror.commons.aDomain.{DocumentField, Header}

class FieldsRule(settings: Settings)
  extends RegularRule {

  override def `match`(context: RequestContext): Task[Boolean] = Task.now {
    if(!context.isReadOnlyRequest) false
    else {
      context.setContextHeader(transientFieldsHeader)
      true
    }
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
