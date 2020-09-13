package tech.beshu.ror.es.request.queries.termlevel

import org.elasticsearch.index.query.TermsSetQueryBuilder
import tech.beshu.ror.accesscontrol.fls.FLS.FieldsUsage.UsingFields.FieldsExtractable
import tech.beshu.ror.accesscontrol.fls.FLS.FieldsUsage.UsingFields.FieldsExtractable.UsedField
import tech.beshu.ror.accesscontrol.fls.FLS.FieldsUsage.UsingFields.FieldsExtractable.UsedField.SpecificField
import tech.beshu.ror.es.request.queries.NotAllowedFieldsInQueryUpdater
import tech.beshu.ror.utils.ReflecUtils.invokeMethodCached

object TermsSetQueryNotAllowedFieldsUpdater extends NotAllowedFieldsInQueryUpdater.WithOneFieldQueryUpdater[TermsSetQueryBuilder] {

  override def fieldFrom(builder: TermsSetQueryBuilder): FieldsExtractable.UsedField = {
    val fieldName = invokeMethodCached(builder, builder.getClass, "getFieldName").asInstanceOf[String]
    UsedField(fieldName)
  }

  override def modifyField(builder: TermsSetQueryBuilder, newField: SpecificField): TermsSetQueryBuilder = {
    val newBuilder = new TermsSetQueryBuilder(newField.value, builder.getValues)

    Option(builder.getMinimumShouldMatchField) match {
      case Some(definedMatchField) => newBuilder.setMinimumShouldMatchField(definedMatchField)
      case None => newBuilder.setMinimumShouldMatchScript(builder.getMinimumShouldMatchScript)
    }
  }
}
