package tech.beshu.ror.es.request.queries.compound

import cats.data.NonEmptyList
import org.elasticsearch.index.query.{BoostingQueryBuilder, QueryBuilders}
import tech.beshu.ror.accesscontrol.fls.FLS.RequestFieldsUsage.UsingFields.FieldsExtractable.UsedField.SpecificField
import tech.beshu.ror.es.request.queries.{NotAllowedFieldsInQueryUpdater, QueryFLS}

object BoostingQueryNotAllowedFieldsUpdater extends NotAllowedFieldsInQueryUpdater[BoostingQueryBuilder] {

  override def modifyNotAllowedFieldsIn(builder: BoostingQueryBuilder,
                                        notAllowedFields: NonEmptyList[SpecificField]): BoostingQueryBuilder = {
    val newPositiveQuery = QueryFLS.modifyFieldsIn(builder.positiveQuery(), notAllowedFields)
    val newNegativeQuery = QueryFLS.modifyFieldsIn(builder.negativeQuery(), notAllowedFields)

    QueryBuilders
      .boostingQuery(newPositiveQuery, newNegativeQuery)
      .negativeBoost(builder.negativeBoost())
      .boost(builder.boost())
  }
}
