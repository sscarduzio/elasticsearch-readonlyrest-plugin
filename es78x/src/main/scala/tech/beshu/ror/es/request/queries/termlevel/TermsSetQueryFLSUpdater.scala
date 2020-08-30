package tech.beshu.ror.es.request.queries.termlevel

import org.elasticsearch.index.query.TermsSetQueryBuilder
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.es.request.queries.QueryFLSUpdater
import tech.beshu.ror.fls.FieldsPolicy
import tech.beshu.ror.utils.ReflecUtils.invokeMethodCached


object TermsSetQueryFLSUpdater extends QueryFLSUpdater[TermsSetQueryBuilder] {

  override def adjustUsedFieldsIn(builder: TermsSetQueryBuilder,
                                  fieldsRestrictions: domain.FieldsRestrictions): TermsSetQueryBuilder = {
    Option(invokeMethodCached(builder, builder.getClass, "getFieldName")) match {
      case Some(fieldName: String) =>
        val fieldsPolicy = new FieldsPolicy(fieldsRestrictions)
        if (fieldsPolicy.canKeep(fieldName)) {
          builder
        } else {
          val someRandomValue = "ROR123123123123123"
          val newBuilder = new TermsSetQueryBuilder(someRandomValue, builder.getValues)

          Option(builder.getMinimumShouldMatchField) match {
            case Some(definedMatchField) => newBuilder.setMinimumShouldMatchField(definedMatchField)
            case None => newBuilder.setMinimumShouldMatchScript(builder.getMinimumShouldMatchScript)
          }
        }
      case None =>
        builder
    }
  }
}
