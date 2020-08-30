package tech.beshu.ror.es.request.queries.compound

import org.elasticsearch.index.query.{BoolQueryBuilder, QueryBuilders}
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.es.request.queries.{BaseFLSQueryUpdater, QueryFLSUpdater}

import scala.collection.JavaConverters._

object BoolQueryFLSUpdater extends QueryFLSUpdater[BoolQueryBuilder] {

  override def adjustUsedFieldsIn(builder: BoolQueryBuilder,
                                  fieldsRestrictions: domain.FieldsRestrictions): BoolQueryBuilder = {
    val newBoolQuery = QueryBuilders.boolQuery()

    val newMust = builder.must().asScala.map(BaseFLSQueryUpdater.adjustUsedFieldsIn(_, fieldsRestrictions))
    val newMustNot = builder.mustNot().asScala.map(BaseFLSQueryUpdater.adjustUsedFieldsIn(_, fieldsRestrictions))
    val newFilter = builder.filter().asScala.map(BaseFLSQueryUpdater.adjustUsedFieldsIn(_, fieldsRestrictions))
    val newShould = builder.should().asScala.map(BaseFLSQueryUpdater.adjustUsedFieldsIn(_, fieldsRestrictions))

    val o1 = newMust.foldLeft(newBoolQuery)(_ must _)
    val o2 = newMustNot.foldLeft(o1)(_ mustNot _)
    val o3 = newFilter.foldLeft(o2)(_ filter _)
    val o4 = newShould.foldLeft(o3)(_ should _)

    o4
      .minimumShouldMatch(builder.minimumShouldMatch())
      .adjustPureNegative(builder.adjustPureNegative())
      .boost(builder.boost())
  }
}
