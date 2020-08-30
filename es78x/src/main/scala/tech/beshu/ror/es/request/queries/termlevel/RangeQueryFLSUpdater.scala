package tech.beshu.ror.es.request.queries.termlevel

import org.elasticsearch.index.query.{QueryBuilders, RangeQueryBuilder}
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.es.request.queries.QueryFLSUpdater
import tech.beshu.ror.fls.FieldsPolicy

object RangeQueryFLSUpdater extends QueryFLSUpdater[RangeQueryBuilder] {

  override def adjustUsedFieldsIn(builder: RangeQueryBuilder,
                                  fieldsRestrictions: domain.FieldsRestrictions): RangeQueryBuilder = {
    val fieldsPolicy = new FieldsPolicy(fieldsRestrictions)
    if (fieldsPolicy.canKeep(builder.fieldName())) {
      builder
    } else {
      val someRandomValue = "ROR123123123123123"
      val newBuilder = QueryBuilders
        .rangeQuery(someRandomValue)
        .boost(builder.boost())

      Option(builder.from()).foreach(lowerBound => newBuilder.from(lowerBound, builder.includeLower()))
      Option(builder.timeZone()).foreach(timezone => newBuilder.timeZone(timezone))
      Option(builder.relation()).foreach(relation => newBuilder.relation(relation.getRelationName))
      Option(builder.format()).foreach(format => newBuilder.format(format))
      Option(builder.to()).foreach(upperBound => newBuilder.to(upperBound, builder.includeUpper()))

      newBuilder
    }
  }
}
