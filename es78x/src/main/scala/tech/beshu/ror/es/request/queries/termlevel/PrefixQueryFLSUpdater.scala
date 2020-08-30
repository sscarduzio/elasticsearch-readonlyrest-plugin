package tech.beshu.ror.es.request.queries.termlevel

import org.elasticsearch.index.query.{PrefixQueryBuilder, QueryBuilders}
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.es.request.queries.QueryFLSUpdater
import tech.beshu.ror.fls.FieldsPolicy

object PrefixQueryFLSUpdater extends QueryFLSUpdater[PrefixQueryBuilder] {

  override def adjustUsedFieldsIn(builder: PrefixQueryBuilder,
                                  fieldsRestrictions: domain.FieldsRestrictions): PrefixQueryBuilder = {
    val fieldsPolicy = new FieldsPolicy(fieldsRestrictions)
    if (fieldsPolicy.canKeep(builder.fieldName())) {
      builder
    } else {
      val someRandomValue = "ROR123123123123123"
      QueryBuilders
        .prefixQuery(someRandomValue, builder.value())
        .rewrite(builder.rewrite())
        .boost(builder.boost())
    }
  }
}
