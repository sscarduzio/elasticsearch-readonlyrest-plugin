package tech.beshu.ror.es.request.queries.termlevel

import org.elasticsearch.index.query.{QueryBuilders, RegexpQueryBuilder}
import tech.beshu.ror.accesscontrol.fls.FLS.RequestFieldsUsage.UsingFields.FieldsExtractable
import tech.beshu.ror.accesscontrol.fls.FLS.RequestFieldsUsage.UsingFields.FieldsExtractable.UsedField
import tech.beshu.ror.accesscontrol.fls.FLS.RequestFieldsUsage.UsingFields.FieldsExtractable.UsedField.SpecificField
import tech.beshu.ror.es.request.queries.NotAllowedFieldsInQueryUpdater

object RegexpQueryNotAllowedFieldsUpdater extends NotAllowedFieldsInQueryUpdater.WithOneFieldQueryUpdater[RegexpQueryBuilder] {

  override def fieldFrom(builder: RegexpQueryBuilder): FieldsExtractable.UsedField = {
    UsedField(builder.fieldName())
  }

  override def modifyField(builder: RegexpQueryBuilder, newField: SpecificField): RegexpQueryBuilder = {
    QueryBuilders
      .regexpQuery(newField.value, builder.value())
      .flags(builder.flags())
      .maxDeterminizedStates(builder.maxDeterminizedStates())
      .rewrite(builder.rewrite())
      .boost(builder.boost())
  }
}
