package tech.beshu.ror.es.request.queries.termlevel

import org.elasticsearch.index.query.{FuzzyQueryBuilder, QueryBuilders}
import tech.beshu.ror.accesscontrol.fls.FLS.FieldsUsage.UsingFields.FieldsExtractable
import tech.beshu.ror.accesscontrol.fls.FLS.FieldsUsage.UsingFields.FieldsExtractable.UsedField
import tech.beshu.ror.accesscontrol.fls.FLS.FieldsUsage.UsingFields.FieldsExtractable.UsedField.SpecificField
import tech.beshu.ror.es.request.queries.NotAllowedFieldsInQueryUpdater

object FuzzyQueryNotAllowedFieldsUpdater extends NotAllowedFieldsInQueryUpdater.WithOneFieldQueryUpdater[FuzzyQueryBuilder] {

  override def fieldFrom(builder: FuzzyQueryBuilder): FieldsExtractable.UsedField = {
    UsedField(builder.fieldName())
  }

  override def modifyField(builder: FuzzyQueryBuilder, newField: SpecificField): FuzzyQueryBuilder = {
    QueryBuilders
      .fuzzyQuery(newField.value, builder.value())
      .fuzziness(builder.fuzziness())
      .maxExpansions(builder.maxExpansions())
      .prefixLength(builder.prefixLength())
      .transpositions(builder.transpositions())
      .rewrite(builder.rewrite())
      .boost(builder.boost())
  }
}
