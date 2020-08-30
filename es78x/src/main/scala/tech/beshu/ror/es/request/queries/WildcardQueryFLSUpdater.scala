package tech.beshu.ror.es.request.queries

import org.elasticsearch.index.query.{QueryBuilders, WildcardQueryBuilder}
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.fls.FieldsPolicy

object WildcardQueryFLSUpdater extends QueryFLSUpdater[WildcardQueryBuilder] {

  override def adjustUsedFieldsIn(builder: WildcardQueryBuilder,
                                  fieldsRestrictions: domain.FieldsRestrictions): WildcardQueryBuilder = {
    val fieldsPolicy = new FieldsPolicy(fieldsRestrictions)
    if (fieldsPolicy.canKeep(builder.fieldName())) {
      builder
    } else {
      val someRandomValue = "ROR123123123123123"
      QueryBuilders
        .wildcardQuery(someRandomValue, builder.value())
        .rewrite(builder.rewrite())
        .boost(builder.boost())
    }
  }
}
