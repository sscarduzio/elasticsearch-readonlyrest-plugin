package tech.beshu.ror.es.request.queries

import org.elasticsearch.index.query.{MatchPhrasePrefixQueryBuilder, QueryBuilders}
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.fls.FieldsPolicy

object MatchPhrasePrefixQueryFLSUpdater extends QueryFLSUpdater[MatchPhrasePrefixQueryBuilder] {

  override def adjustUsedFieldsIn(builder: MatchPhrasePrefixQueryBuilder,
                                  fieldsRestrictions: domain.FieldsRestrictions): MatchPhrasePrefixQueryBuilder = {
    val fieldsPolicy = new FieldsPolicy(fieldsRestrictions)
    if (fieldsPolicy.canKeep(builder.fieldName())) {
      builder
    } else {
      val someRandomValue = "ROR123123123123123"

      QueryBuilders.matchPhrasePrefixQuery(someRandomValue, builder.value())
        .analyzer(builder.analyzer())
        .maxExpansions(builder.maxExpansions())
        .slop(builder.slop())
        .boost(builder.boost())
    }
  }
}
