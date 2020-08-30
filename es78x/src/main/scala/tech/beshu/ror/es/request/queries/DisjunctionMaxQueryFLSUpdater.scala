package tech.beshu.ror.es.request.queries

import org.elasticsearch.index.query.{DisMaxQueryBuilder, QueryBuilders}
import tech.beshu.ror.accesscontrol.domain

import scala.collection.JavaConverters._

object DisjunctionMaxQueryFLSUpdater extends QueryFLSUpdater[DisMaxQueryBuilder] {

  override def adjustUsedFieldsIn(builder: DisMaxQueryBuilder,
                                  fieldsRestrictions: domain.FieldsRestrictions): DisMaxQueryBuilder = {
    builder.innerQueries().asScala
      .map(BaseQueryUpdater.adjustUsedFieldsIn(_, fieldsRestrictions))
      .foldLeft(QueryBuilders.disMaxQuery())(_ add _)
      .tieBreaker(builder.tieBreaker())
      .boost(builder.tieBreaker())
  }
}
