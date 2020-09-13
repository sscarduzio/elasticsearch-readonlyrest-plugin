package tech.beshu.ror.es.request.queries.termlevel

import org.elasticsearch.index.query.{QueryBuilders, TermQueryBuilder}
import tech.beshu.ror.accesscontrol.fls.FLS.RequestFieldsUsage.UsingFields.FieldsExtractable
import tech.beshu.ror.accesscontrol.fls.FLS.RequestFieldsUsage.UsingFields.FieldsExtractable.UsedField
import tech.beshu.ror.accesscontrol.fls.FLS.RequestFieldsUsage.UsingFields.FieldsExtractable.UsedField.SpecificField
import tech.beshu.ror.es.request.queries.NotAllowedFieldsInQueryUpdater

object TermQueryNotAllowedFieldsUpdater extends NotAllowedFieldsInQueryUpdater.WithOneFieldQueryUpdater[TermQueryBuilder] {

  override def fieldFrom(builder: TermQueryBuilder): FieldsExtractable.UsedField = {
    UsedField(builder.fieldName())
  }

  override def modifyField(builder: TermQueryBuilder, newField: SpecificField): TermQueryBuilder = {
    QueryBuilders
      .termQuery(newField.value, builder.value())
      .boost(builder.boost())
  }
}
