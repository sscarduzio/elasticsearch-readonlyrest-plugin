package tech.beshu.ror.es.request.queries.fulltext

import org.elasticsearch.index.query.{MatchQueryBuilder, QueryBuilders}
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.es.request.queries.QueryFLSUpdater
import tech.beshu.ror.fls.FieldsPolicy

object MatchQueryFLSUpdater extends QueryFLSUpdater[MatchQueryBuilder] {

  override def adjustUsedFieldsIn(builder: MatchQueryBuilder,
                                  fieldsRestrictions: domain.FieldsRestrictions): MatchQueryBuilder = {
    val fieldsPolicy = new FieldsPolicy(fieldsRestrictions)
    if (fieldsPolicy.canKeep(builder.fieldName())) {
      builder
    } else {
      val someRandomValue = "ROR123123123123123"
      QueryBuilders
        .matchQuery(someRandomValue, builder.value())
        .boost(builder.boost())
    }
  }
}
