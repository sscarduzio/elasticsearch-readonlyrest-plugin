package tech.beshu.ror.es.request.queries

import org.elasticsearch.index.query.{ConstantScoreQueryBuilder, QueryBuilders}
import tech.beshu.ror.accesscontrol.domain

object ConstantScoreQueryFLSUpdater extends QueryFLSUpdater[ConstantScoreQueryBuilder] {

  override def adjustUsedFieldsIn(builder: ConstantScoreQueryBuilder,
                                  fieldsRestrictions: domain.FieldsRestrictions): ConstantScoreQueryBuilder = {
    val newInnerQuery = BaseQueryUpdater.adjustUsedFieldsIn(builder.innerQuery(), fieldsRestrictions)

    QueryBuilders
      .constantScoreQuery(newInnerQuery)
      .boost(builder.boost())
  }
}
