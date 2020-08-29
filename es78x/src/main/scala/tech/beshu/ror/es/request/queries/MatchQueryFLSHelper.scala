package tech.beshu.ror.es.request.queries

import org.elasticsearch.index.query.{MatchQueryBuilder, QueryBuilders}
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.fls.FieldsPolicy

class MatchQueryFLSHelper extends QueryFLSHelper[MatchQueryBuilder] {

  override def modifyUsing(builder: MatchQueryBuilder,
                           fieldsRestrictions: domain.FieldsRestrictions): MatchQueryBuilder = {
    val fieldsPolicy = new FieldsPolicy(fieldsRestrictions)
    if (fieldsPolicy.canKeep(builder.fieldName())) {
      builder
    } else {
      val someRandomValue = "ROR123123123123123"
      QueryBuilders.matchQuery(someRandomValue, builder.value())
    }
  }
}
