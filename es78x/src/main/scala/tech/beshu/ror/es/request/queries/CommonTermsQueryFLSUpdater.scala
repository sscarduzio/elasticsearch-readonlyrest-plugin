package tech.beshu.ror.es.request.queries

import org.elasticsearch.index.query.{CommonTermsQueryBuilder, QueryBuilders}
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.fls.FieldsPolicy

object CommonTermsQueryFLSUpdater extends QueryFLSUpdater[CommonTermsQueryBuilder] {

  override def adjustUsedFieldsIn(builder: CommonTermsQueryBuilder,
                                  fieldsRestrictions: domain.FieldsRestrictions): CommonTermsQueryBuilder = {
    val fieldsPolicy = new FieldsPolicy(fieldsRestrictions)
    if (fieldsPolicy.canKeep(builder.fieldName())) {
      builder
    } else {
      val someRandomValue = "ROR123123123123123"

      QueryBuilders.commonTermsQuery(someRandomValue, builder.value())
        .analyzer(builder.analyzer())
        .cutoffFrequency(builder.cutoffFrequency())
        .highFreqMinimumShouldMatch(builder.highFreqMinimumShouldMatch())
        .highFreqOperator(builder.highFreqOperator())
        .lowFreqMinimumShouldMatch(builder.lowFreqMinimumShouldMatch())
        .lowFreqOperator(builder.lowFreqOperator())
        .boost(builder.boost())
    }
  }
}
