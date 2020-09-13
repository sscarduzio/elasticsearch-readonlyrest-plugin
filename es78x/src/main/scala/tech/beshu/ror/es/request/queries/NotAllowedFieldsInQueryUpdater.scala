package tech.beshu.ror.es.request.queries

import cats.data.NonEmptyList
import org.elasticsearch.index.query.QueryBuilder
import tech.beshu.ror.accesscontrol.fls.FLS.FieldsUsage.UsingFields.FieldsExtractable.UsedField
import tech.beshu.ror.accesscontrol.fls.FLS.FieldsUsage.UsingFields.FieldsExtractable.UsedField.SpecificField

trait NotAllowedFieldsInQueryUpdater[BUILDER <: QueryBuilder] {

  def modifyNotAllowedFieldsIn(builder: BUILDER,
                               notAllowedFields: NonEmptyList[SpecificField]): BUILDER
}

object NotAllowedFieldsInQueryUpdater {
  trait WithOneFieldQueryUpdater[BUILDER <: QueryBuilder] extends NotAllowedFieldsInQueryUpdater[BUILDER] {

    def fieldFrom(builder: BUILDER): UsedField
    def modifyField(builder: BUILDER, newField: SpecificField): BUILDER

    override def modifyNotAllowedFieldsIn(builder: BUILDER,
                                          notAllowedFields: NonEmptyList[SpecificField]): BUILDER = {
      if (notAllowedFields.toList.contains(SpecificField(fieldFrom(builder).value))) {
        modifyField(builder, SpecificField("ROR123123123123123"))
      } else {
        builder
      }
    }
  }
}
