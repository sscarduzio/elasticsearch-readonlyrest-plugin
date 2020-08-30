package tech.beshu.ror.es.request.queries.compound

import org.elasticsearch.index.query.{BoostingQueryBuilder, QueryBuilders}
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.es.request.queries.{BaseFLSQueryUpdater, QueryFLSUpdater}

object BoostingQueryFLSUpdater extends QueryFLSUpdater[BoostingQueryBuilder] {

  override def adjustUsedFieldsIn(builder: BoostingQueryBuilder,
                                  fieldsRestrictions: domain.FieldsRestrictions): BoostingQueryBuilder = {
    val newPositiveQuery = BaseFLSQueryUpdater.adjustUsedFieldsIn(builder.positiveQuery(), fieldsRestrictions)
    val newNegativeQuery = BaseFLSQueryUpdater.adjustUsedFieldsIn(builder.negativeQuery(), fieldsRestrictions)

    QueryBuilders
      .boostingQuery(newPositiveQuery, newNegativeQuery)
      .negativeBoost(builder.negativeBoost())
      .boost(builder.boost())
  }
}
