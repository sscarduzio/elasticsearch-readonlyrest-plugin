/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.es.handler.request.queries

import cats.data.NonEmptyList
import cats.syntax.list._
import org.elasticsearch.index.query._
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.RequestFieldsUsage.UsedField.SpecificField
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.RequestFieldsUsage.{CannotExtractFields, NotUsingFields, UsingFields}
import tech.beshu.ror.es.handler.request.queries.QueryType.Leaf

import scala.annotation.nowarn
import scala.jdk.CollectionConverters._

trait QueryWithModifiableFields[QUERY <: QueryBuilder] {

  def handleNotAllowedFieldsIn(query: QUERY,
                               notAllowedFields: NonEmptyList[SpecificField]): QUERY
}

object QueryWithModifiableFields {

  def apply[QUERY <: QueryBuilder](implicit ev: QueryWithModifiableFields[QUERY]): QueryWithModifiableFields[QUERY] = ev

  def instance[QUERY <: QueryBuilder](f: (QUERY, NonEmptyList[SpecificField]) => QUERY): QueryWithModifiableFields[QUERY] = f(_, _)

  implicit class Ops[QUERY <: QueryBuilder : QueryWithModifiableFields](val query: QUERY) {

    def handleNotAllowedFields(notAllowedFields: NonEmptyList[SpecificField]): QUERY = {
      QueryWithModifiableFields[QUERY].handleNotAllowedFieldsIn(query, notAllowedFields)
    }
  }

  @nowarn("cat=unused")
  abstract class ModifiableLeafQuery[QUERY <: QueryBuilder : Leaf : QueryFieldsUsage] extends QueryWithModifiableFields[QUERY] {

    protected def replace(query: QUERY,
                          notAllowedFields: NonEmptyList[SpecificField]): QUERY

    override def handleNotAllowedFieldsIn(query: QUERY,
                                          notAllowedFields: NonEmptyList[SpecificField]): QUERY = {
      QueryFieldsUsage[QUERY].fieldsIn(query) match {
        case UsingFields(usedFields) =>
          usedFields
            .collect {
              case specificField: SpecificField => specificField
            }
            .filter(notAllowedFields.toList.contains)
            .toNel match {
            case Some(detectedNotAllowedFields) =>
              replace(query, detectedNotAllowedFields)
            case None =>
              query
          }
        case CannotExtractFields | NotUsingFields =>
          query
      }
    }
  }

  object ModifiableLeafQuery {

    def apply[QUERY <: QueryBuilder](implicit ev: ModifiableLeafQuery[QUERY]) = ev

    def instance[QUERY <: QueryBuilder : Leaf : QueryFieldsUsage](f: (QUERY, NonEmptyList[SpecificField]) => QUERY) = new ModifiableLeafQuery[QUERY] {
      override protected def replace(query: QUERY, notAllowedFields: NonEmptyList[SpecificField]): QUERY = f(query, notAllowedFields)
    }
  }

  object instances {

    import QueryFieldsUsage.instances._
    import QueryType.instances._

    //term level
    implicit val existsQueryHandler: ModifiableLeafQuery[ExistsQueryBuilder] = ModifiableLeafQuery.instance { (query, notAllowedFields) =>
      QueryBuilders
        .existsQuery(notAllowedFields.head.obfuscate.value)
        .boost(query.boost())
    }

    implicit val fuzzyQueryHandler: ModifiableLeafQuery[FuzzyQueryBuilder] = ModifiableLeafQuery.instance { (query, notAllowedFields) =>
      QueryBuilders
        .fuzzyQuery(notAllowedFields.head.obfuscate.value, query.value())
        .fuzziness(query.fuzziness())
        .maxExpansions(query.maxExpansions())
        .prefixLength(query.prefixLength())
        .transpositions(query.transpositions())
        .rewrite(query.rewrite())
        .boost(query.boost())
    }

    implicit val prefixQueryHandler: ModifiableLeafQuery[PrefixQueryBuilder] = ModifiableLeafQuery.instance { (query, notAllowedFields) =>
      QueryBuilders
        .prefixQuery(notAllowedFields.head.obfuscate.value, query.value())
        .rewrite(query.rewrite())
        .boost(query.boost())
    }

    implicit val rangeQueryHandler: ModifiableLeafQuery[RangeQueryBuilder] = ModifiableLeafQuery.instance { (query, notAllowedFields) =>
      val newQuery = QueryBuilders.rangeQuery(notAllowedFields.head.obfuscate.value)
        .boost(query.boost())

      Option(query.from()).foreach(lowerBound => newQuery.from(lowerBound, query.includeLower()))
      Option(query.timeZone()).foreach(timezone => newQuery.timeZone(timezone))
      Option(query.relation()).foreach(relation => newQuery.relation(relation.getRelationName))
      Option(query.format()).foreach(format => newQuery.format(format))
      Option(query.to()).foreach(upperBound => newQuery.to(upperBound, query.includeUpper()))
      newQuery
    }

    implicit val regexpQueryHandler: ModifiableLeafQuery[RegexpQueryBuilder] = ModifiableLeafQuery.instance { (query, notAllowedFields) =>
      QueryBuilders
        .regexpQuery(notAllowedFields.head.obfuscate.value, query.value())
        .flags(query.flags())
        .maxDeterminizedStates(query.maxDeterminizedStates())
        .rewrite(query.rewrite())
        .boost(query.boost())
    }

    implicit val termQueryHandler: ModifiableLeafQuery[TermQueryBuilder] = ModifiableLeafQuery.instance { (query, notAllowedFields) =>
      QueryBuilders
        .termQuery(notAllowedFields.head.obfuscate.value, query.value())
        .boost(query.boost())
    }

    implicit val wildcardQueryHandler: ModifiableLeafQuery[WildcardQueryBuilder] = ModifiableLeafQuery.instance { (query, notAllowedFields) =>
      QueryBuilders
        .wildcardQuery(notAllowedFields.head.obfuscate.value, query.value())
        .rewrite(query.rewrite())
        .boost(query.boost())

    }

    //full text
    implicit val commonTermsQueryHandler: ModifiableLeafQuery[CommonTermsQueryBuilder] = ModifiableLeafQuery.instance {
      (query, notAllowedFields) =>
        QueryBuilders.commonTermsQuery(notAllowedFields.head.obfuscate.value, query.value())
          .analyzer(query.analyzer())
          .cutoffFrequency(query.cutoffFrequency())
          .highFreqMinimumShouldMatch(query.highFreqMinimumShouldMatch())
          .highFreqOperator(query.highFreqOperator())
          .lowFreqMinimumShouldMatch(query.lowFreqMinimumShouldMatch())
          .lowFreqOperator(query.lowFreqOperator())
          .boost(query.boost())
    }

    implicit val matchQueryHandler: ModifiableLeafQuery[MatchQueryBuilder] = ModifiableLeafQuery.instance { (query, notAllowedFields) =>
      QueryBuilders
        .matchQuery(notAllowedFields.head.obfuscate.value, query.value())
        .boost(query.boost())
    }

    implicit val matchPhraseQueryHandler: ModifiableLeafQuery[MatchPhraseQueryBuilder] = ModifiableLeafQuery.instance { (query, notAllowedFields) =>
      QueryBuilders.matchPhraseQuery(notAllowedFields.head.obfuscate.value, query.value())
        .analyzer(query.analyzer())
        .slop(query.slop())
        .boost(query.boost())
    }

    implicit val matchPhrasePrefixQueryHandler: ModifiableLeafQuery[MatchPhrasePrefixQueryBuilder] = ModifiableLeafQuery.instance { (query, notAllowedFields) =>
      QueryBuilders.matchPhrasePrefixQuery(notAllowedFields.head.obfuscate.value, query.value())
        .analyzer(query.analyzer())
        .maxExpansions(query.maxExpansions())
        .slop(query.slop())
        .boost(query.boost())
    }

    //compound
    implicit val boolQueryHandler: QueryWithModifiableFields[BoolQueryBuilder] = QueryWithModifiableFields.instance { (query, notAllowedFields) =>
      final case class ClauseHandler(extractor: BoolQueryBuilder => java.util.List[QueryBuilder],
                                     creator: (BoolQueryBuilder, QueryBuilder) => BoolQueryBuilder)

      def handleNotAllowedFieldsInClause(boolClauseExtractor: BoolQueryBuilder => java.util.List[QueryBuilder]) = {
        boolClauseExtractor(query).asScala.map(_.handleNotAllowedFields(notAllowedFields))
      }

      val clauseHandlers = List(
        ClauseHandler(_.must(), _ must _),
        ClauseHandler(_.mustNot(), _ must _),
        ClauseHandler(_.filter(), _ filter _),
        ClauseHandler(_.should(), _ should _)
      )

      val boolQueryWithNewClauses = clauseHandlers
        .map(clause => (handleNotAllowedFieldsInClause(clause.extractor), clause.creator))
        .foldLeft(QueryBuilders.boolQuery()) {
          case (modifiedBoolQuery, (modifiedClauses, clauseCreator)) =>
            modifiedClauses.foldLeft(modifiedBoolQuery)(clauseCreator)
        }

      boolQueryWithNewClauses
        .minimumShouldMatch(query.minimumShouldMatch())
        .adjustPureNegative(query.adjustPureNegative())
        .boost(query.boost())
    }

    implicit val boostingQueryHandler: QueryWithModifiableFields[BoostingQueryBuilder] = QueryWithModifiableFields.instance { (query, notAllowedFields) =>
      val newPositiveQuery = query.positiveQuery().handleNotAllowedFields(notAllowedFields)
      val newNegativeQuery = query.negativeQuery().handleNotAllowedFields(notAllowedFields)

      QueryBuilders
        .boostingQuery(newPositiveQuery, newNegativeQuery)
        .negativeBoost(query.negativeBoost())
        .boost(query.boost())
    }

    implicit val constantScoreQueryHandler: QueryWithModifiableFields[ConstantScoreQueryBuilder] = QueryWithModifiableFields.instance { (query, notAllowedFields) =>
      val newInnerQuery = query.innerQuery().handleNotAllowedFields(notAllowedFields)

      QueryBuilders
        .constantScoreQuery(newInnerQuery)
        .boost(query.boost())
    }

    implicit val disjuctionMaxQueryHandler: QueryWithModifiableFields[DisMaxQueryBuilder] = QueryWithModifiableFields.instance { (query, notAllowedFields) =>
      query.innerQueries().asScala
        .map(_.handleNotAllowedFields(notAllowedFields))
        .foldLeft(QueryBuilders.disMaxQuery())(_ add _)
        .tieBreaker(query.tieBreaker())
        .boost(query.tieBreaker())
    }

    implicit val rootQueryHandler: QueryWithModifiableFields[QueryBuilder] = (query: QueryBuilder, notAllowedFields: NonEmptyList[SpecificField]) => query match {
      case builder: BoolQueryBuilder => handleCompoundQuery(builder, notAllowedFields)
      case builder: BoostingQueryBuilder => handleCompoundQuery(builder, notAllowedFields)
      case builder: ConstantScoreQueryBuilder => handleCompoundQuery(builder, notAllowedFields)
      case builder: DisMaxQueryBuilder => handleCompoundQuery(builder, notAllowedFields)

      //fulltext
      case builder: CommonTermsQueryBuilder => handleLeafQuery(builder, notAllowedFields)
      case builder: MatchQueryBuilder => handleLeafQuery(builder, notAllowedFields)
      case builder: MatchPhraseQueryBuilder => handleLeafQuery(builder, notAllowedFields)
      case builder: MatchPhrasePrefixQueryBuilder => handleLeafQuery(builder, notAllowedFields)

      //termlevel
      case builder: ExistsQueryBuilder => handleLeafQuery(builder, notAllowedFields)
      case builder: FuzzyQueryBuilder => handleLeafQuery(builder, notAllowedFields)
      case builder: PrefixQueryBuilder => handleLeafQuery(builder, notAllowedFields)
      case builder: RangeQueryBuilder => handleLeafQuery(builder, notAllowedFields)
      case builder: RegexpQueryBuilder => handleLeafQuery(builder, notAllowedFields)
      case builder: TermQueryBuilder => handleLeafQuery(builder, notAllowedFields)
      case builder: WildcardQueryBuilder => handleLeafQuery(builder, notAllowedFields)

      case other => other
    }

    private def handleLeafQuery[QUERY <: QueryBuilder : ModifiableLeafQuery](leafQuery: QUERY,
                                                                             notAllowedFields: NonEmptyList[SpecificField]) = {
      ModifiableLeafQuery[QUERY].handleNotAllowedFieldsIn(leafQuery, notAllowedFields)
    }

    private def handleCompoundQuery[QUERY <: QueryBuilder : QueryWithModifiableFields](compoundQuery: QUERY,
                                                                                       notAllowedFields: NonEmptyList[SpecificField]) = {
      QueryWithModifiableFields[QUERY].handleNotAllowedFieldsIn(compoundQuery, notAllowedFields)
    }
  }
}
