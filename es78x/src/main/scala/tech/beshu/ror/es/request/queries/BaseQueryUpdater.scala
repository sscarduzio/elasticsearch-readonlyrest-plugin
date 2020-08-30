package tech.beshu.ror.es.request.queries

import org.elasticsearch.index.query.functionscore.FunctionScoreQueryBuilder
import org.elasticsearch.index.query._
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.es.request.queries.BaseQueryUpdater.QueryModificationEligibility.{ModificationImpossible, ModificationPossible}

object BaseQueryUpdater extends QueryFLSUpdater[QueryBuilder] {

  sealed trait QueryModificationEligibility
  object QueryModificationEligibility {

    final case class ModificationPossible[BUILDER <: QueryBuilder](helper: QueryFLSUpdater[BUILDER],
                                                                   builder: BUILDER) extends QueryModificationEligibility

    case object ModificationImpossible extends QueryModificationEligibility

  }

  override def adjustUsedFieldsIn(builder: QueryBuilder,
                                  fieldsRestrictions: domain.FieldsRestrictions): QueryBuilder = {
    resolveModificationEligibility(builder) match {
      case ModificationPossible(helper, builder) => helper.adjustUsedFieldsIn(builder, fieldsRestrictions)
      case ModificationImpossible => builder
    }
  }

  def resolveModificationEligibility(queryBuilder: QueryBuilder): QueryModificationEligibility = queryBuilder match {
    case builder: BoolQueryBuilder =>
      ModificationPossible(BoolQueryFLSUpdater, builder)
    case builder: BoostingQueryBuilder =>
      ModificationPossible(BoostingQueryFLSUpdater, builder)
    case builder: CommonTermsQueryBuilder =>
      ModificationPossible(CommonTermsQueryFLSUpdater, builder)
    case builder: ConstantScoreQueryBuilder =>
      ModificationPossible(ConstantScoreQueryFLSUpdater, builder)
    case builder: DisMaxQueryBuilder =>
      ModificationPossible(DisjunctionMaxQueryFLSUpdater, builder)
    case builder: ExistsQueryBuilder =>
      ModificationPossible(ExistsQueryFLSUpdater, builder)
    case builder: FuzzyQueryBuilder =>
      ModificationPossible(FuzzyQueryFLSUpdater, builder)
    case builder: MatchQueryBuilder =>
      ModificationPossible(MatchQueryFLSUpdater, builder)
    case builder: MatchBoolPrefixQueryBuilder =>
      ModificationPossible(MatchBoolPrefixQueryFLSUpdater, builder)
    case builder: MatchPhraseQueryBuilder =>
      ModificationPossible(MatchPhraseQueryFLSUpdater, builder)
    case builder: MatchPhrasePrefixQueryBuilder =>
      ModificationPossible(MatchPhrasePrefixQueryFLSUpdater, builder)
    case builder: PrefixQueryBuilder =>
      ModificationPossible(PrefixQueryFLSUpdater, builder)
    case builder: RangeQueryBuilder =>
      ModificationPossible(RangeQueryFLSUpdater, builder)
    case builder: RegexpQueryBuilder =>
      ModificationPossible(RegexpQueryFLSUpdater, builder)
    case builder: TermQueryBuilder =>
      ModificationPossible(TermQueryFLSUpdater, builder)
    case builder: TermsSetQueryBuilder =>
      ModificationPossible(TermsSetQueryFLSUpdater, builder)
    case builder: WildcardQueryBuilder =>
      ModificationPossible(WildcardQueryFLSUpdater, builder)
    case _: FunctionScoreQueryBuilder =>
      ModificationImpossible
    case _: MultiMatchQueryBuilder =>
      ModificationImpossible
    case _: IntervalQueryBuilder =>
      ModificationImpossible
    case _: QueryStringQueryBuilder =>
      ModificationImpossible
    case _: SimpleQueryStringBuilder =>
      ModificationImpossible
    case _ =>
      ModificationImpossible
  }

}
