package tech.beshu.ror.es.request.queries.termlevel

import org.elasticsearch.index.query.{ExistsQueryBuilder, QueryBuilders}
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.es.request.queries.QueryFLSUpdater
import tech.beshu.ror.fls.FieldsPolicy

object ExistsQueryFLSUpdater extends QueryFLSUpdater[ExistsQueryBuilder] {

  override def adjustUsedFieldsIn(builder: ExistsQueryBuilder,
                                  fieldsRestrictions: domain.FieldsRestrictions): ExistsQueryBuilder = {
    val fieldsPolicy = new FieldsPolicy(fieldsRestrictions)
    if (fieldsPolicy.canKeep(builder.fieldName())) {
      builder
    } else {
      val someRandomValue = "ROR123123123123123"
      QueryBuilders
        .existsQuery(someRandomValue)
        .boost(builder.boost())
    }
  }
}
