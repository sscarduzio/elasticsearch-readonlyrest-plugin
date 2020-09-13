package tech.beshu.ror.es.request.queries.compound

import cats.data.NonEmptyList
import org.elasticsearch.index.query.{DisMaxQueryBuilder, QueryBuilders}
import tech.beshu.ror.accesscontrol.fls.FLS.RequestFieldsUsage.UsingFields.FieldsExtractable.UsedField.SpecificField
import tech.beshu.ror.es.request.queries.{NotAllowedFieldsInQueryUpdater, QueryFLS}

import scala.collection.JavaConverters._

object DisjunctionMaxQueryNotAllowedFieldsUpdater extends NotAllowedFieldsInQueryUpdater[DisMaxQueryBuilder] {

  override def modifyNotAllowedFieldsIn(builder: DisMaxQueryBuilder,
                                        notAllowedFields: NonEmptyList[SpecificField]): DisMaxQueryBuilder = {
    builder.innerQueries().asScala
      .map(QueryFLS.modifyFieldsIn(_, notAllowedFields))
      .foldLeft(QueryBuilders.disMaxQuery())(_ add _)
      .tieBreaker(builder.tieBreaker())
      .boost(builder.tieBreaker())
  }
}
