package tech.beshu.ror.es.request.queries

import cats.data.NonEmptyList
import org.elasticsearch.index.query._
import tech.beshu.ror.accesscontrol.fls.FLS.FieldsUsage
import tech.beshu.ror.accesscontrol.fls.FLS.FieldsUsage.NotUsingFields
import tech.beshu.ror.accesscontrol.fls.FLS.FieldsUsage.UsingFields.FieldsExtractable.UsedField.SpecificField
import tech.beshu.ror.accesscontrol.fls.FLS.FieldsUsage.UsingFields.{CantExtractFields, FieldsExtractable}
import tech.beshu.ror.es.request.queries.QueryType.Compound
import tech.beshu.ror.es.request.queries.QueryType.instances._
import tech.beshu.ror.es.request.queries.compound.{BoolQueryNotAllowedFieldsUpdater, BoostingQueryNotAllowedFieldsUpdater, ConstantScoreQueryNotAllowedFieldsUpdater, DisjunctionMaxQueryNotAllowedFieldsUpdater}
import tech.beshu.ror.es.request.queries.fulltext._
import tech.beshu.ror.es.request.queries.termlevel._
import tech.beshu.ror.utils.ReflecUtils.invokeMethodCached

object QueryFLS {

  def resolveQueryFieldsUsageIn(queryBuilder: QueryBuilder): FieldsUsage = queryBuilder match {
    //compound
    case builder: BoolQueryBuilder =>
      resolveFieldsUsageForCompoundQuery(builder)
    case builder: BoostingQueryBuilder =>
      resolveFieldsUsageForCompoundQuery(builder)
    case builder: ConstantScoreQueryBuilder =>
      resolveFieldsUsageForCompoundQuery(builder)
    case builder: DisMaxQueryBuilder =>
      resolveFieldsUsageForCompoundQuery(builder)

    //fulltext
    case builder: CommonTermsQueryBuilder => FieldsExtractable.one(builder.fieldName())
    case builder: MatchBoolPrefixQueryBuilder => FieldsExtractable.one(builder.fieldName())
    case builder: MatchQueryBuilder => FieldsExtractable.one(builder.fieldName())
    case builder: MatchPhraseQueryBuilder => FieldsExtractable.one(builder.fieldName())
    case builder: MatchPhrasePrefixQueryBuilder => FieldsExtractable.one(builder.fieldName())

    //termlevel
    case builder: ExistsQueryBuilder => FieldsExtractable.one(builder.fieldName())
    case builder: FuzzyQueryBuilder => FieldsExtractable.one(builder.fieldName())
    case builder: PrefixQueryBuilder => FieldsExtractable.one(builder.fieldName())
    case builder: RangeQueryBuilder => FieldsExtractable.one(builder.fieldName())
    case builder: RegexpQueryBuilder => FieldsExtractable.one(builder.fieldName())
    case builder: TermQueryBuilder => FieldsExtractable.one(builder.fieldName())
    case builder: TermsSetQueryBuilder => Option(invokeMethodCached(builder, builder.getClass, "getFieldName")) match {
      case Some(fieldName: String) => FieldsExtractable.one(fieldName)
      case _ => CantExtractFields
    }
    case builder: WildcardQueryBuilder => FieldsExtractable.one(builder.fieldName())

    case _ => CantExtractFields
  }


  def modifyFieldsIn(queryBuilder: QueryBuilder,
                     notAllowedFields: NonEmptyList[SpecificField]): QueryBuilder = queryBuilder match {
    //compound
    case builder: BoolQueryBuilder =>
      BoolQueryNotAllowedFieldsUpdater.modifyNotAllowedFieldsIn(builder, notAllowedFields)
    case builder: BoostingQueryBuilder =>
      BoostingQueryNotAllowedFieldsUpdater.modifyNotAllowedFieldsIn(builder, notAllowedFields)
    case builder: ConstantScoreQueryBuilder =>
      ConstantScoreQueryNotAllowedFieldsUpdater.modifyNotAllowedFieldsIn(builder, notAllowedFields)
    case builder: DisMaxQueryBuilder =>
      DisjunctionMaxQueryNotAllowedFieldsUpdater.modifyNotAllowedFieldsIn(builder, notAllowedFields)

    //fulltext
    case builder: CommonTermsQueryBuilder =>
      CommonTermsQueryNotAllowedFieldsUpdater.modifyNotAllowedFieldsIn(builder, notAllowedFields)
    case builder: MatchBoolPrefixQueryBuilder =>
      MatchBoolPrefixQueryNotAllowedFieldsUpdater.modifyNotAllowedFieldsIn(builder, notAllowedFields)
    case builder: MatchQueryBuilder =>
      MatchQueryNotAllowedFieldsUpdater.modifyNotAllowedFieldsIn(builder, notAllowedFields)
    case builder: MatchPhraseQueryBuilder =>
      MatchPhraseQueryNotAllowedFieldsUpdater.modifyNotAllowedFieldsIn(builder, notAllowedFields)
    case builder: MatchPhrasePrefixQueryBuilder =>
      MatchPhrasePrefixQueryNotAllowedFieldsUpdater.modifyNotAllowedFieldsIn(builder, notAllowedFields)

    //termlevel
    case builder: ExistsQueryBuilder =>
      ExistsQueryNotAllowedFieldsUpdater.modifyNotAllowedFieldsIn(builder, notAllowedFields)
    case builder: FuzzyQueryBuilder =>
      FuzzyQueryNotAllowedFieldsUpdater.modifyNotAllowedFieldsIn(builder, notAllowedFields)
    case builder: PrefixQueryBuilder =>
      PrefixQueryNotAllowedFieldsUpdater.modifyNotAllowedFieldsIn(builder, notAllowedFields)
    case builder: RangeQueryBuilder =>
      RangeQueryNotAllowedFieldsUpdater.modifyNotAllowedFieldsIn(builder, notAllowedFields)
    case builder: RegexpQueryBuilder =>
      RegexpQueryNotAllowedFieldsUpdater.modifyNotAllowedFieldsIn(builder, notAllowedFields)
    case builder: TermQueryBuilder =>
      TermQueryNotAllowedFieldsUpdater.modifyNotAllowedFieldsIn(builder, notAllowedFields)
    case builder: TermsSetQueryBuilder =>
      TermsSetQueryNotAllowedFieldsUpdater.modifyNotAllowedFieldsIn(builder, notAllowedFields)
    case builder: WildcardQueryBuilder =>
      WildcardQueryNotAllowedFieldsUpdater.modifyNotAllowedFieldsIn(builder, notAllowedFields)

    case other => other
  }

  private def resolveFieldsUsageForCompoundQuery[QUERY <: QueryBuilder : Compound](compoundQuery: QUERY): FieldsUsage = {
    val innerQueries = implicitly[Compound[QUERY]].innerQueriesOf(compoundQuery)
    val innerQueriesFieldsExtractability = innerQueries.map(resolveQueryFieldsUsageIn)
    val atLeastOneInnerQueryIsNotExtractable = innerQueriesFieldsExtractability.contains(CantExtractFields)
    val allInnerQueriesDoNotUseFields = innerQueriesFieldsExtractability.forall(_ == NotUsingFields)

    if (atLeastOneInnerQueryIsNotExtractable) {
      CantExtractFields
    } else if (allInnerQueriesDoNotUseFields) {
      NotUsingFields
    } else {
      val allUsedFields = innerQueriesFieldsExtractability
        .collect {
          case fieldsExtractable: FieldsExtractable => fieldsExtractable
        }
        .flatMap(_.usedFields)
      FieldsExtractable(allUsedFields)
    }
  }
}
