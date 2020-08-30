package tech.beshu.ror.es.request.queries

import org.elasticsearch.index.query.{QueryBuilders, RegexpQueryBuilder}
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.fls.FieldsPolicy

object RegexpQueryFLSUpdater extends QueryFLSUpdater[RegexpQueryBuilder] {

  override def adjustUsedFieldsIn(builder: RegexpQueryBuilder,
                                  fieldsRestrictions: domain.FieldsRestrictions): RegexpQueryBuilder = {
    val fieldsPolicy = new FieldsPolicy(fieldsRestrictions)
    if (fieldsPolicy.canKeep(builder.fieldName())) {
      builder
    } else {
      val someRandomValue = "ROR123123123123123"
      QueryBuilders
        .regexpQuery(someRandomValue, builder.value())
        .flags(builder.flags())
        .maxDeterminizedStates(builder.maxDeterminizedStates())
        .rewrite(builder.rewrite())
        .boost(builder.boost())
    }
  }
}
