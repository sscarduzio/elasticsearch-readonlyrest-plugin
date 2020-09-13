package tech.beshu.ror.es.request.queries.termlevel

import org.elasticsearch.index.query.{QueryBuilders, WildcardQueryBuilder}
import tech.beshu.ror.accesscontrol.fls.FLS.FieldsUsage.UsingFields.FieldsExtractable
import tech.beshu.ror.accesscontrol.fls.FLS.FieldsUsage.UsingFields.FieldsExtractable.UsedField
import tech.beshu.ror.accesscontrol.fls.FLS.FieldsUsage.UsingFields.FieldsExtractable.UsedField.SpecificField
import tech.beshu.ror.es.request.queries.NotAllowedFieldsInQueryUpdater

object WildcardQueryNotAllowedFieldsUpdater extends NotAllowedFieldsInQueryUpdater.WithOneFieldQueryUpdater[WildcardQueryBuilder] {

  override def fieldFrom(builder: WildcardQueryBuilder): FieldsExtractable.UsedField = {
    UsedField(builder.fieldName())
  }

  override def modifyField(builder: WildcardQueryBuilder, newField: SpecificField): WildcardQueryBuilder = {
    QueryBuilders
      .wildcardQuery(newField.value, builder.value())
      .rewrite(builder.rewrite())
      .boost(builder.boost())
  }
}
