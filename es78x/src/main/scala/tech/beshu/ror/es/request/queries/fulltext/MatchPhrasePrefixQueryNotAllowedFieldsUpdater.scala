package tech.beshu.ror.es.request.queries.fulltext

import org.elasticsearch.index.query.{MatchPhrasePrefixQueryBuilder, QueryBuilders}
import tech.beshu.ror.accesscontrol.fls.FLS.FieldsUsage.UsingFields.FieldsExtractable
import tech.beshu.ror.accesscontrol.fls.FLS.FieldsUsage.UsingFields.FieldsExtractable.UsedField
import tech.beshu.ror.accesscontrol.fls.FLS.FieldsUsage.UsingFields.FieldsExtractable.UsedField.SpecificField
import tech.beshu.ror.es.request.queries.NotAllowedFieldsInQueryUpdater

object MatchPhrasePrefixQueryNotAllowedFieldsUpdater extends NotAllowedFieldsInQueryUpdater.WithOneFieldQueryUpdater[MatchPhrasePrefixQueryBuilder] {

  override def fieldFrom(builder: MatchPhrasePrefixQueryBuilder): FieldsExtractable.UsedField = {
    UsedField(builder.fieldName())
  }

  override def modifyField(builder: MatchPhrasePrefixQueryBuilder, newField: SpecificField): MatchPhrasePrefixQueryBuilder = {
    QueryBuilders.matchPhrasePrefixQuery(newField.value, builder.value())
      .analyzer(builder.analyzer())
      .maxExpansions(builder.maxExpansions())
      .slop(builder.slop())
      .boost(builder.boost())
  }
}
