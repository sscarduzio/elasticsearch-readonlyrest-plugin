package tech.beshu.ror.es.request.queries.termlevel

import org.elasticsearch.index.query.{PrefixQueryBuilder, QueryBuilders}
import tech.beshu.ror.accesscontrol.fls.FLS.RequestFieldsUsage.UsingFields.FieldsExtractable
import tech.beshu.ror.accesscontrol.fls.FLS.RequestFieldsUsage.UsingFields.FieldsExtractable.UsedField
import tech.beshu.ror.accesscontrol.fls.FLS.RequestFieldsUsage.UsingFields.FieldsExtractable.UsedField.SpecificField
import tech.beshu.ror.es.request.queries.NotAllowedFieldsInQueryUpdater

object PrefixQueryNotAllowedFieldsUpdater extends NotAllowedFieldsInQueryUpdater.WithOneFieldQueryUpdater[PrefixQueryBuilder] {

  override def fieldFrom(builder: PrefixQueryBuilder): FieldsExtractable.UsedField = {
    UsedField(builder.fieldName())
  }

  override def modifyField(builder: PrefixQueryBuilder, newField: SpecificField): PrefixQueryBuilder = {
    QueryBuilders
      .prefixQuery(newField.value, builder.value())
      .rewrite(builder.rewrite())
      .boost(builder.boost())

  }
}
