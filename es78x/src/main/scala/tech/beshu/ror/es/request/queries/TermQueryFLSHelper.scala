package tech.beshu.ror.es.request.queries

import org.elasticsearch.index.query.{QueryBuilders, TermQueryBuilder}
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.fls.FieldsPolicy

class TermQueryFLSHelper extends QueryFLSHelper[TermQueryBuilder] {

  override def modifyUsing(builder: TermQueryBuilder,
                           fieldsRestrictions: domain.FieldsRestrictions): TermQueryBuilder = {
    val fieldsPolicy = new FieldsPolicy(fieldsRestrictions)
    if (fieldsPolicy.canKeep(builder.fieldName())) {
      builder
    } else {
      val someRandomValue = "ROR123123123123123"
      QueryBuilders.termQuery(someRandomValue, builder.value())
    }
  }
}
