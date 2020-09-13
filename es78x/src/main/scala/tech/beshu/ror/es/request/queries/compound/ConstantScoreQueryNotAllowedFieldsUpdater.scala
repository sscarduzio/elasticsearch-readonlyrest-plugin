package tech.beshu.ror.es.request.queries.compound

import cats.data.NonEmptyList
import org.elasticsearch.index.query.{ConstantScoreQueryBuilder, QueryBuilders}
import tech.beshu.ror.accesscontrol.fls.FLS.RequestFieldsUsage.UsingFields.FieldsExtractable.UsedField.SpecificField
import tech.beshu.ror.es.request.queries.{NotAllowedFieldsInQueryUpdater, QueryFLS}

object ConstantScoreQueryNotAllowedFieldsUpdater extends NotAllowedFieldsInQueryUpdater[ConstantScoreQueryBuilder] {

  override def modifyNotAllowedFieldsIn(builder: ConstantScoreQueryBuilder,
                                        notAllowedFields: NonEmptyList[SpecificField]): ConstantScoreQueryBuilder = {
    val newInnerQuery = QueryFLS.modifyFieldsIn(builder.innerQuery(), notAllowedFields)

    QueryBuilders
      .constantScoreQuery(newInnerQuery)
      .boost(builder.boost())
  }
}
