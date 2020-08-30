package tech.beshu.ror.es.request.queries

import org.elasticsearch.index.query.{MatchPhraseQueryBuilder, QueryBuilders}
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.fls.FieldsPolicy

object MatchPhraseQueryFLSUpdater extends QueryFLSUpdater[MatchPhraseQueryBuilder] {

  override def adjustUsedFieldsIn(builder: MatchPhraseQueryBuilder,
                                  fieldsRestrictions: domain.FieldsRestrictions): MatchPhraseQueryBuilder = {
    val fieldsPolicy = new FieldsPolicy(fieldsRestrictions)
    if (fieldsPolicy.canKeep(builder.fieldName())) {
      builder
    } else {
      val someRandomValue = "ROR123123123123123"

      QueryBuilders.matchPhraseQuery(someRandomValue, builder.value())
        .analyzer(builder.analyzer())
        .zeroTermsQuery(builder.zeroTermsQuery())
        .slop(builder.slop())
        .boost(builder.boost())
    }
  }
}
