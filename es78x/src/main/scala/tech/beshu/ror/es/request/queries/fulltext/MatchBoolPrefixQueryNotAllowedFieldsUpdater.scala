package tech.beshu.ror.es.request.queries.fulltext

import org.elasticsearch.index.query.MatchBoolPrefixQueryBuilder
import tech.beshu.ror.accesscontrol.fls.FLS.RequestFieldsUsage.UsingFields.FieldsExtractable
import tech.beshu.ror.accesscontrol.fls.FLS.RequestFieldsUsage.UsingFields.FieldsExtractable.UsedField
import tech.beshu.ror.accesscontrol.fls.FLS.RequestFieldsUsage.UsingFields.FieldsExtractable.UsedField.SpecificField
import tech.beshu.ror.es.request.queries.NotAllowedFieldsInQueryUpdater

object MatchBoolPrefixQueryNotAllowedFieldsUpdater extends NotAllowedFieldsInQueryUpdater.WithOneFieldQueryUpdater[MatchBoolPrefixQueryBuilder] {

  override def fieldFrom(builder: MatchBoolPrefixQueryBuilder): FieldsExtractable.UsedField = {
    UsedField(builder.fieldName())
  }

  override def modifyField(builder: MatchBoolPrefixQueryBuilder, newField: SpecificField): MatchBoolPrefixQueryBuilder = {
    val newBuilder = new MatchBoolPrefixQueryBuilder(newField.value, builder.value())
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
