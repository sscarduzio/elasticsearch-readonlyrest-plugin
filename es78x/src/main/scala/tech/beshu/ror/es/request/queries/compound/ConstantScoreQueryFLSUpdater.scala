package tech.beshu.ror.es.request.queries.compound

import org.elasticsearch.index.query.{ConstantScoreQueryBuilder, QueryBuilders}
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.es.request.queries.{BaseFLSQueryUpdater, QueryFLSUpdater}

object ConstantScoreQueryFLSUpdater extends QueryFLSUpdater[ConstantScoreQueryBuilder] {

  override def adjustUsedFieldsIn(builder: ConstantScoreQueryBuilder,
                                  fieldsRestrictions: domain.FieldsRestrictions): ConstantScoreQueryBuilder = {
    val newInnerQuery = BaseFLSQueryUpdater.adjustUsedFieldsIn(builder.innerQuery(), fieldsRestrictions)

    QueryBuilders
      .constantScoreQuery(newInnerQuery)
      .boost(builder.boost())
  }
}
