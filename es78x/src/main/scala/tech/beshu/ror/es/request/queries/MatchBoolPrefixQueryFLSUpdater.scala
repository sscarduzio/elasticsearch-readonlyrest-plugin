package tech.beshu.ror.es.request.queries

import org.elasticsearch.index.query.MatchBoolPrefixQueryBuilder
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.fls.FieldsPolicy

object MatchBoolPrefixQueryFLSUpdater extends QueryFLSUpdater[MatchBoolPrefixQueryBuilder] {

  override def adjustUsedFieldsIn(builder: MatchBoolPrefixQueryBuilder,
                                  fieldsRestrictions: domain.FieldsRestrictions): MatchBoolPrefixQueryBuilder = {
    val fieldsPolicy = new FieldsPolicy(fieldsRestrictions)
    if (fieldsPolicy.canKeep(builder.fieldName())) {
      builder
    } else {
      val someRandomValue = "ROR123123123123123"

      val newBuilder = new MatchBoolPrefixQueryBuilder(someRandomValue, builder.value())
        .analyzer(builder.analyzer())
        .minimumShouldMatch(builder.minimumShouldMatch())
        .fuzzyRewrite(builder.fuzzyRewrite())
        .fuzzyTranspositions(builder.fuzzyTranspositions())
        .maxExpansions(builder.maxExpansions())
        .operator(builder.operator())
        .prefixLength(builder.prefixLength())
        .boost(builder.boost())

      Option(builder.fuzziness()).foreach(newBuilder.fuzziness)

      newBuilder
    }
  }
}
