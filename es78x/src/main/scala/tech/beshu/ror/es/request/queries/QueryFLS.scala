package tech.beshu.ror.es.request.queries

import cats.data.NonEmptyList
import org.elasticsearch.index.query._
import tech.beshu.ror.accesscontrol.fls.FLS.RequestFieldsUsage.UsedField
import tech.beshu.ror.es.request.queries.QueryFLS.QueryFieldsUsage.FieldsExtractionResult.{CantExtractFields, NotUsingFields, UsingFields}
import tech.beshu.ror.es.request.queries.QueryFLS.QueryFieldsUsage.instances._
import tech.beshu.ror.es.request.queries.QueryType.instances._
import tech.beshu.ror.es.request.queries.QueryType.{Compound, Leaf}
import tech.beshu.ror.es.request.queries.compound.{BoolQueryNotAllowedFieldsUpdater, BoostingQueryNotAllowedFieldsUpdater, ConstantScoreQueryNotAllowedFieldsUpdater, DisjunctionMaxQueryNotAllowedFieldsUpdater}
import tech.beshu.ror.es.request.queries.fulltext._
import tech.beshu.ror.es.request.queries.termlevel._
import tech.beshu.ror.utils.ReflecUtils.invokeMethodCached

object QueryFLS {

  trait QueryFieldsUsage[QUERY <: QueryBuilder] {
    def fieldsIn(query: QUERY): QueryFieldsUsage.FieldsExtractionResult
  }

  object QueryFieldsUsage {

    def one[QUERY <: QueryBuilder](fieldNameExtractor: QUERY => String): QueryFieldsUsage[QUERY] =
      query => UsingFields(NonEmptyList.one(UsedField(fieldNameExtractor(query))))

    sealed trait FieldsExtractionResult
    object FieldsExtractionResult {
      case object CantExtractFields extends FieldsExtractionResult
      case object NotUsingFields extends FieldsExtractionResult
      final case class UsingFields(usedFields: NonEmptyList[UsedField]) extends FieldsExtractionResult
    }

    object instances {

      implicit val commonTermsQueryFields: QueryFieldsUsage[CommonTermsQueryBuilder] = QueryFieldsUsage.one(_.fieldName())
      implicit val matchBoolPrefixQueryFields: QueryFieldsUsage[MatchBoolPrefixQueryBuilder] = QueryFieldsUsage.one(_.fieldName())
      implicit val matchQueryFields: QueryFieldsUsage[MatchQueryBuilder] = QueryFieldsUsage.one(_.fieldName())
      implicit val matchPhraseQueryFields: QueryFieldsUsage[MatchPhraseQueryBuilder] = QueryFieldsUsage.one(_.fieldName())
      implicit val matchPhrasePrefixQueryFields: QueryFieldsUsage[MatchPhrasePrefixQueryBuilder] = QueryFieldsUsage.one(_.fieldName())

      implicit val existsQueryFields: QueryFieldsUsage[ExistsQueryBuilder] = QueryFieldsUsage.one(_.fieldName())
      implicit val fuzzyQueryFields: QueryFieldsUsage[FuzzyQueryBuilder] = QueryFieldsUsage.one(_.fieldName())
      implicit val prefixQueryFields: QueryFieldsUsage[PrefixQueryBuilder] = QueryFieldsUsage.one(_.fieldName())
      implicit val rangeQueryFields: QueryFieldsUsage[RangeQueryBuilder] = QueryFieldsUsage.one(_.fieldName())
      implicit val regexpQueryFields: QueryFieldsUsage[RegexpQueryBuilder] = QueryFieldsUsage.one(_.fieldName())
      implicit val termQueryFields: QueryFieldsUsage[TermQueryBuilder] = QueryFieldsUsage.one(_.fieldName())
      implicit val wildcardQueryFields: QueryFieldsUsage[WildcardQueryBuilder] = QueryFieldsUsage.one(_.fieldName())
      implicit val termsSetQueryFields: QueryFieldsUsage[TermsSetQueryBuilder] = query => {
        Option(invokeMethodCached(query, query.getClass, "getFieldName")) match {
          case Some(fieldName: String) => UsingFields(NonEmptyList.one(UsedField(fieldName)))
          case _ => CantExtractFields
        }
      }
    }
  }

  def resolveQueryFieldsUsageIn(queryBuilder: QueryBuilder): QueryFieldsUsage.FieldsExtractionResult = queryBuilder match {
    //compound
    case builder: BoolQueryBuilder => resolveFieldsUsageForCompoundQuery(builder)
    case builder: BoostingQueryBuilder => resolveFieldsUsageForCompoundQuery(builder)
    case builder: ConstantScoreQueryBuilder => resolveFieldsUsageForCompoundQuery(builder)
    case builder: DisMaxQueryBuilder => resolveFieldsUsageForCompoundQuery(builder)

    //leaf
    case builder: CommonTermsQueryBuilder => resolveFieldsUsageForLeafQuery(builder)
    case builder: MatchBoolPrefixQueryBuilder => resolveFieldsUsageForLeafQuery(builder)
    case builder: MatchQueryBuilder => resolveFieldsUsageForLeafQuery(builder)
    case builder: MatchPhraseQueryBuilder => resolveFieldsUsageForLeafQuery(builder)
    case builder: MatchPhrasePrefixQueryBuilder => resolveFieldsUsageForLeafQuery(builder)
    case builder: ExistsQueryBuilder => resolveFieldsUsageForLeafQuery(builder)
    case builder: FuzzyQueryBuilder => resolveFieldsUsageForLeafQuery(builder)
    case builder: PrefixQueryBuilder => resolveFieldsUsageForLeafQuery(builder)
    case builder: RangeQueryBuilder => resolveFieldsUsageForLeafQuery(builder)
    case builder: RegexpQueryBuilder => resolveFieldsUsageForLeafQuery(builder)
    case builder: TermQueryBuilder => resolveFieldsUsageForLeafQuery(builder)
    case builder: TermsSetQueryBuilder => resolveFieldsUsageForLeafQuery(builder)
    case builder: WildcardQueryBuilder => resolveFieldsUsageForLeafQuery(builder)
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

  private def resolveFieldsUsageForCompoundQuery[QUERY <: QueryBuilder : Compound](compoundQuery: QUERY): QueryFieldsUsage.FieldsExtractionResult = {
    val innerQueries = implicitly[Compound[QUERY]].innerQueriesOf(compoundQuery)

    NonEmptyList.fromList(innerQueries) match {
      case Some(definedInnerQueries) =>
        val innerQueriesFieldsExtractability = definedInnerQueries
          .map(resolveQueryFieldsUsageIn)

        if (innerQueriesFieldsExtractability.exists(_ == CantExtractFields)) {
          CantExtractFields
        } else if (innerQueriesFieldsExtractability.forall(_ == NotUsingFields)) {
          NotUsingFields
        } else {
          val allUsedFields = innerQueriesFieldsExtractability
            .collect {
              case usingFields: UsingFields => usingFields
            }
            .map(_.usedFields)

          NonEmptyList.fromList(allUsedFields)
            .map(_.flatMap(identity))
            .map(UsingFields)
            .getOrElse(NotUsingFields)
        }
      case None =>
        NotUsingFields
    }
  }

  private def resolveFieldsUsageForLeafQuery[QUERY <: QueryBuilder : QueryFieldsUsage : Leaf](leafQuery: QUERY): QueryFieldsUsage.FieldsExtractionResult = {
    implicitly[QueryFieldsUsage[QUERY]]
      .fieldsIn(leafQuery)
  }
}
