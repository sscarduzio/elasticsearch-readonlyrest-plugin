package tech.beshu.ror.es.request.queries.fulltext

import org.elasticsearch.index.query.{CommonTermsQueryBuilder, QueryBuilders}
import tech.beshu.ror.accesscontrol.fls.FLS.FieldsUsage.UsingFields.FieldsExtractable
import tech.beshu.ror.accesscontrol.fls.FLS.FieldsUsage.UsingFields.FieldsExtractable.UsedField
import tech.beshu.ror.accesscontrol.fls.FLS.FieldsUsage.UsingFields.FieldsExtractable.UsedField.SpecificField
import tech.beshu.ror.es.request.queries.NotAllowedFieldsInQueryUpdater

object CommonTermsQueryNotAllowedFieldsUpdater extends NotAllowedFieldsInQueryUpdater.WithOneFieldQueryUpdater[CommonTermsQueryBuilder] {

  override def fieldFrom(builder: CommonTermsQueryBuilder): FieldsExtractable.UsedField = UsedField(builder.fieldName())

  override def modifyField(builder: CommonTermsQueryBuilder, newField: SpecificField): CommonTermsQueryBuilder = {
    QueryBuilders.commonTermsQuery(newField.value, builder.value())
      .analyzer(builder.analyzer())
      .cutoffFrequency(builder.cutoffFrequency())
      .highFreqMinimumShouldMatch(builder.highFreqMinimumShouldMatch())
      .highFreqOperator(builder.highFreqOperator())
      .lowFreqMinimumShouldMatch(builder.lowFreqMinimumShouldMatch())
      .lowFreqOperator(builder.lowFreqOperator())
      .boost(builder.boost())
  }
}
