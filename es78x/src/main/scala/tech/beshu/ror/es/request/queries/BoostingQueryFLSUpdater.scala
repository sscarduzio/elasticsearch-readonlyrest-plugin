package tech.beshu.ror.es.request.queries

import org.elasticsearch.index.query.{BoostingQueryBuilder, QueryBuilders}
import tech.beshu.ror.accesscontrol.domain

object BoostingQueryFLSUpdater extends QueryFLSUpdater[BoostingQueryBuilder] {

  override def adjustUsedFieldsIn(builder: BoostingQueryBuilder,
                                  fieldsRestrictions: domain.FieldsRestrictions): BoostingQueryBuilder = {
    val newPositiveQuery = BaseQueryUpdater.adjustUsedFieldsIn(builder.positiveQuery(), fieldsRestrictions)
    val newNegativeQuery = BaseQueryUpdater.adjustUsedFieldsIn(builder.negativeQuery(), fieldsRestrictions)

    QueryBuilders
      .boostingQuery(newPositiveQuery, newNegativeQuery)
      .negativeBoost(builder.negativeBoost())
      .boost(builder.boost())
  }
}
