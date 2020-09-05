package tech.beshu.ror.es.request.queries

import org.elasticsearch.index.query._
import tech.beshu.ror.accesscontrol.domain

object BaseFLSQueryUpdater extends QueryFLSUpdater[QueryBuilder] {

  import QueryModificationEligibility._

  override def adjustUsedFieldsIn(builder: QueryBuilder,
                                  fieldsRestrictions: domain.FieldsRestrictions): QueryBuilder = {
    resolveModificationEligibility(builder) match {
      case ModificationPossible(helper, builder) => helper.adjustUsedFieldsIn(builder, fieldsRestrictions)
      case ModificationImpossible => builder
    }
  }
}
