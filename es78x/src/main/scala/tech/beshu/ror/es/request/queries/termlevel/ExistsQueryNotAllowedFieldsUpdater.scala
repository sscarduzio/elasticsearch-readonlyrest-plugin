package tech.beshu.ror.es.request.queries.termlevel

import org.elasticsearch.index.query.{ExistsQueryBuilder, QueryBuilders}
import tech.beshu.ror.accesscontrol.fls.FLS.FieldsUsage.UsingFields.FieldsExtractable
import tech.beshu.ror.accesscontrol.fls.FLS.FieldsUsage.UsingFields.FieldsExtractable.UsedField
import tech.beshu.ror.accesscontrol.fls.FLS.FieldsUsage.UsingFields.FieldsExtractable.UsedField.SpecificField
import tech.beshu.ror.es.request.queries.NotAllowedFieldsInQueryUpdater

object ExistsQueryNotAllowedFieldsUpdater extends NotAllowedFieldsInQueryUpdater.WithOneFieldQueryUpdater[ExistsQueryBuilder] {

  override def fieldFrom(builder: ExistsQueryBuilder): FieldsExtractable.UsedField = {
    UsedField(builder.fieldName())
  }

  override def modifyField(builder: ExistsQueryBuilder, newField: SpecificField): ExistsQueryBuilder = {
    QueryBuilders
      .existsQuery(newField.value)
      .boost(builder.boost())
  }
}
