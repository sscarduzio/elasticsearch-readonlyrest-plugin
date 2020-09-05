package tech.beshu.ror.es.request.queries

import org.elasticsearch.index.query.functionscore.FunctionScoreQueryBuilder
import org.elasticsearch.index.query._
import tech.beshu.ror.es.request.queries.compound.{BoolQueryFLSUpdater, BoostingQueryFLSUpdater, ConstantScoreQueryFLSUpdater, DisjunctionMaxQueryFLSUpdater}
import tech.beshu.ror.es.request.queries.fulltext._
import tech.beshu.ror.es.request.queries.ops.hasWildcard
import tech.beshu.ror.es.request.queries.termlevel._

import scala.collection.JavaConverters._

sealed trait QueryModificationEligibility

object QueryModificationEligibility {

  final case class ModificationPossible[BUILDER <: QueryBuilder](helper: QueryFLSUpdater[BUILDER],
                                                                 builder: BUILDER) extends QueryModificationEligibility

  case object ModificationImpossible extends QueryModificationEligibility

  def resolveModificationEligibility(queryBuilder: QueryBuilder): QueryModificationEligibility = queryBuilder match {

    //compound
    case builder: BoolQueryBuilder =>
      val isAtLeastOneQueryUnmodifable = List(builder.must(), builder.mustNot(), builder.filter(), builder.should())
        .flatMap(_.asScala.toList)
        .map(resolveModificationEligibility)
        .contains(ModificationImpossible)

      if (isAtLeastOneQueryUnmodifable)
        ModificationImpossible
      else
        ModificationPossible(BoolQueryFLSUpdater, builder)
    case builder: BoostingQueryBuilder =>
      val isAtLeastOneQueryUnmodifable = List(builder.negativeQuery(), builder.positiveQuery())
        .map(resolveModificationEligibility)
        .contains(ModificationImpossible)

      if (isAtLeastOneQueryUnmodifable)
        ModificationImpossible
      else
        ModificationPossible(BoostingQueryFLSUpdater, builder)
    case builder: ConstantScoreQueryBuilder =>
      resolveModificationEligibility(builder.innerQuery()) match {
        case ModificationImpossible => ModificationImpossible
        case _: ModificationPossible[_] => ModificationPossible(ConstantScoreQueryFLSUpdater, builder)
      }
    case builder: DisMaxQueryBuilder =>
      val isAtLeastOneQueryUnmodifable = builder.innerQueries().asScala.toList
        .map(resolveModificationEligibility)
        .contains(ModificationImpossible)

      if (isAtLeastOneQueryUnmodifable)
        ModificationImpossible
      else
        ModificationPossible(DisjunctionMaxQueryFLSUpdater, builder)

    //fulltext
    case builder: CommonTermsQueryBuilder =>
      ModificationPossible(CommonTermsQueryFLSUpdater, builder)
    case builder: MatchBoolPrefixQueryBuilder =>
      ModificationPossible(MatchBoolPrefixQueryFLSUpdater, builder)
    case builder: MatchQueryBuilder =>
      ModificationPossible(MatchQueryFLSUpdater, builder)
    case builder: MatchPhraseQueryBuilder =>
      ModificationPossible(MatchPhraseQueryFLSUpdater, builder)
    case builder: MatchPhrasePrefixQueryBuilder =>
      ModificationPossible(MatchPhrasePrefixQueryFLSUpdater, builder)

    //termlevel
    case builder: ExistsQueryBuilder =>
      if (hasWildcard(builder.fieldName()))
        ModificationImpossible
      else
        ModificationPossible(ExistsQueryFLSUpdater, builder)
    case builder: FuzzyQueryBuilder =>
      ModificationPossible(FuzzyQueryFLSUpdater, builder)
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


    //cant handle
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