package tech.beshu.ror.es.request.queries.fulltext

import org.elasticsearch.index.query.{MatchPhraseQueryBuilder, QueryBuilders}
import tech.beshu.ror.accesscontrol.fls.FLS.FieldsUsage.UsingFields.FieldsExtractable
import tech.beshu.ror.accesscontrol.fls.FLS.FieldsUsage.UsingFields.FieldsExtractable.UsedField
import tech.beshu.ror.accesscontrol.fls.FLS.FieldsUsage.UsingFields.FieldsExtractable.UsedField.SpecificField
import tech.beshu.ror.es.request.queries.NotAllowedFieldsInQueryUpdater

object MatchPhraseQueryNotAllowedFieldsUpdater extends NotAllowedFieldsInQueryUpdater.WithOneFieldQueryUpdater[MatchPhraseQueryBuilder] {

  override def fieldFrom(builder: MatchPhraseQueryBuilder): FieldsExtractable.UsedField = {
    UsedField(builder.fieldName())
  }

  override def modifyField(builder: MatchPhraseQueryBuilder, newField: SpecificField): MatchPhraseQueryBuilder = {
    QueryBuilders.matchPhraseQuery(newField.value, builder.value())
      .analyzer(builder.analyzer())
      .zeroTermsQuery(builder.zeroTermsQuery())
      .slop(builder.slop())
      .boost(builder.boost())
  }
}
