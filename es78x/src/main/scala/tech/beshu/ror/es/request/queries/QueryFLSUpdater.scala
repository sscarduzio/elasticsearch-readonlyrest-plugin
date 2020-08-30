package tech.beshu.ror.es.request.queries

import org.elasticsearch.index.query.QueryBuilder
import tech.beshu.ror.accesscontrol.domain.FieldsRestrictions

trait QueryFLSUpdater[BUILDER <: QueryBuilder] {

  def adjustUsedFieldsIn(builder: BUILDER,
                         fieldsRestrictions: FieldsRestrictions): BUILDER

}
