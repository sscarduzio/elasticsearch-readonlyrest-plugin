package tech.beshu.ror.es.request.queries.termlevel

import org.elasticsearch.index.query.{QueryBuilders, RangeQueryBuilder}
import tech.beshu.ror.accesscontrol.fls.FLS.FieldsUsage.UsingFields.FieldsExtractable
import tech.beshu.ror.accesscontrol.fls.FLS.FieldsUsage.UsingFields.FieldsExtractable.UsedField
import tech.beshu.ror.accesscontrol.fls.FLS.FieldsUsage.UsingFields.FieldsExtractable.UsedField.SpecificField
import tech.beshu.ror.es.request.queries.NotAllowedFieldsInQueryUpdater

object RangeQueryNotAllowedFieldsUpdater extends NotAllowedFieldsInQueryUpdater.WithOneFieldQueryUpdater[RangeQueryBuilder] {

  override def fieldFrom(builder: RangeQueryBuilder): FieldsExtractable.UsedField = {
    UsedField(builder.fieldName())
  }

  override def modifyField(builder: RangeQueryBuilder, newField: SpecificField): RangeQueryBuilder = {
    val newBuilder = QueryBuilders.rangeQuery(newField.value)
      .boost(builder.boost())

    Option(builder.from()).foreach(lowerBound => newBuilder.from(lowerBound, builder.includeLower()))
    Option(builder.timeZone()).foreach(timezone => newBuilder.timeZone(timezone))
    Option(builder.relation()).foreach(relation => newBuilder.relation(relation.getRelationName))
    Option(builder.format()).foreach(format => newBuilder.format(format))
    Option(builder.to()).foreach(upperBound => newBuilder.to(upperBound, builder.includeUpper()))

    newBuilder

  }
}
