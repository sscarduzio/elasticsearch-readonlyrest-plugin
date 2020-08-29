package tech.beshu.ror.es.request.queries

import org.elasticsearch.index.query.QueryBuilder
import tech.beshu.ror.accesscontrol.domain.FieldsRestrictions

trait QueryFLSHelper[BUILDER <: QueryBuilder] {

  def modifyUsing(builder: BUILDER,
                  fieldsRestrictions: FieldsRestrictions): BUILDER

}
