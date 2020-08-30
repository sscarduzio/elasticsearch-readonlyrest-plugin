package tech.beshu.ror.es.request.queries.compound

import org.elasticsearch.index.query.{DisMaxQueryBuilder, QueryBuilders}
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.es.request.queries.{BaseFLSQueryUpdater, QueryFLSUpdater}

import scala.collection.JavaConverters._

object DisjunctionMaxQueryFLSUpdater extends QueryFLSUpdater[DisMaxQueryBuilder] {

  override def adjustUsedFieldsIn(builder: DisMaxQueryBuilder,
                                  fieldsRestrictions: domain.FieldsRestrictions): DisMaxQueryBuilder = {
    builder.innerQueries().asScala
      .map(BaseFLSQueryUpdater.adjustUsedFieldsIn(_, fieldsRestrictions))
      .foldLeft(QueryBuilders.disMaxQuery())(_ add _)
      .tieBreaker(builder.tieBreaker())
      .boost(builder.tieBreaker())
  }
}
