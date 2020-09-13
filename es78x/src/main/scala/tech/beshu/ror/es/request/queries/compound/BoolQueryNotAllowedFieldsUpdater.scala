package tech.beshu.ror.es.request.queries.compound

import cats.data.NonEmptyList
import org.elasticsearch.index.query.{BoolQueryBuilder, QueryBuilders}
import tech.beshu.ror.accesscontrol.fls.FLS.FieldsUsage.UsingFields.FieldsExtractable.UsedField.SpecificField
import tech.beshu.ror.es.request.queries.{NotAllowedFieldsInQueryUpdater, QueryFLS}

import scala.collection.JavaConverters._

object BoolQueryNotAllowedFieldsUpdater extends NotAllowedFieldsInQueryUpdater[BoolQueryBuilder] {

  override def modifyNotAllowedFieldsIn(builder: BoolQueryBuilder,
                                        notAllowedFields: NonEmptyList[SpecificField]): BoolQueryBuilder = {
    val newBoolQuery = QueryBuilders.boolQuery()

    val newMust = builder.must().asScala.map(QueryFLS.modifyFieldsIn(_, notAllowedFields))
    val newMustNot = builder.mustNot().asScala.map(QueryFLS.modifyFieldsIn(_, notAllowedFields))
    val newFilter = builder.filter().asScala.map(QueryFLS.modifyFieldsIn(_, notAllowedFields))
    val newShould = builder.should().asScala.map(QueryFLS.modifyFieldsIn(_, notAllowedFields))

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
