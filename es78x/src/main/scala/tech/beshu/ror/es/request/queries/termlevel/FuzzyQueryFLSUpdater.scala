package tech.beshu.ror.es.request.queries.termlevel

import org.elasticsearch.index.query.{FuzzyQueryBuilder, QueryBuilders}
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.es.request.queries.QueryFLSUpdater
import tech.beshu.ror.fls.FieldsPolicy

object FuzzyQueryFLSUpdater extends QueryFLSUpdater[FuzzyQueryBuilder] {

  override def adjustUsedFieldsIn(builder: FuzzyQueryBuilder,
                                  fieldsRestrictions: domain.FieldsRestrictions): FuzzyQueryBuilder = {
    val fieldsPolicy = new FieldsPolicy(fieldsRestrictions)
    if (fieldsPolicy.canKeep(builder.fieldName())) {
      builder
    } else {
      val someRandomValue = "ROR123123123123123"
      QueryBuilders
        .fuzzyQuery(someRandomValue, builder.value())
        .fuzziness(builder.fuzziness())
        .maxExpansions(builder.maxExpansions())
        .prefixLength(builder.prefixLength())
        .transpositions(builder.transpositions())
        .rewrite(builder.rewrite())
        .boost(builder.boost())
    }
  }
}
