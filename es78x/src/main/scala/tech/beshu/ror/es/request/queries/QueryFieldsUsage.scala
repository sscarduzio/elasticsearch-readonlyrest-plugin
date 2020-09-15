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
package tech.beshu.ror.es.request.queries

import cats.data.NonEmptyList
import org.elasticsearch.index.query._
import tech.beshu.ror.accesscontrol.fls.FLS.RequestFieldsUsage
import tech.beshu.ror.accesscontrol.fls.FLS.RequestFieldsUsage.{CantExtractFields, NotUsingFields, UsedField, UsingFields}
import tech.beshu.ror.es.request.queries.QueryType.instances._
import tech.beshu.ror.es.request.queries.QueryType.{Compound, Leaf}
import tech.beshu.ror.utils.ReflecUtils.invokeMethodCached

trait QueryFieldsUsage[QUERY <: QueryBuilder] {
  def fieldsIn(query: QUERY): RequestFieldsUsage
}

object QueryFieldsUsage {
  def apply[QUERY <: QueryBuilder](implicit ev: QueryFieldsUsage[QUERY]) = ev

  implicit class Ops[QUERY <: QueryBuilder : QueryFieldsUsage](val query: QUERY) {
    def fieldsUsage = QueryFieldsUsage[QUERY].fieldsIn(query)
  }

  def one[QUERY <: QueryBuilder](fieldNameExtractor: QUERY => String): QueryFieldsUsage[QUERY] =
    query => UsingFields(NonEmptyList.one(UsedField(fieldNameExtractor(query))))

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

    implicit val rootQueryFields: QueryFieldsUsage[QueryBuilder] = {
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

    private def resolveFieldsUsageForLeafQuery[QUERY <: QueryBuilder : QueryFieldsUsage : Leaf](leafQuery: QUERY) = {
      leafQuery.fieldsUsage
    }

    private def resolveFieldsUsageForCompoundQuery[QUERY <: QueryBuilder : Compound](compoundQuery: QUERY): RequestFieldsUsage = {
      val innerQueries = Compound[QUERY].innerQueriesOf(compoundQuery)

      NonEmptyList.fromList(innerQueries) match {
        case Some(definedInnerQueries) =>
          val innerQueriesFieldsUsage = definedInnerQueries.map(_.fieldsUsage)

          if (innerQueriesFieldsUsage.exists(_ == CantExtractFields)) {
            CantExtractFields
          } else if (innerQueriesFieldsUsage.forall(_ == NotUsingFields)) {
            NotUsingFields
          } else {
            val allUsedFields = innerQueriesFieldsUsage
              .collect {
                case usingFields: UsingFields => usingFields.usedFields
              }

            NonEmptyList.fromList(allUsedFields)
              .map(_.flatMap(identity))
              .map(UsingFields)
              .getOrElse(NotUsingFields)
          }
        case None =>
          NotUsingFields
      }
    }
  }
}