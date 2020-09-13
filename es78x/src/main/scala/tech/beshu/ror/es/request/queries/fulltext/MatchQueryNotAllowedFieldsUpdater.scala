package tech.beshu.ror.es.request.queries.fulltext

import org.elasticsearch.index.query.{MatchQueryBuilder, QueryBuilders}
import tech.beshu.ror.accesscontrol.fls.FLS.FieldsUsage.UsingFields.FieldsExtractable
import tech.beshu.ror.accesscontrol.fls.FLS.FieldsUsage.UsingFields.FieldsExtractable.UsedField
import tech.beshu.ror.accesscontrol.fls.FLS.FieldsUsage.UsingFields.FieldsExtractable.UsedField.SpecificField
import tech.beshu.ror.es.request.queries.NotAllowedFieldsInQueryUpdater

object MatchQueryNotAllowedFieldsUpdater extends NotAllowedFieldsInQueryUpdater.WithOneFieldQueryUpdater[MatchQueryBuilder] {

  override def fieldFrom(builder: MatchQueryBuilder): FieldsExtractable.UsedField = {
    UsedField(builder.fieldName())
  }

  override def modifyField(builder: MatchQueryBuilder, newField: SpecificField): MatchQueryBuilder = {
    QueryBuilders
      .matchQuery(newField.value, builder.value())
      .boost(builder.boost())
  }
}
