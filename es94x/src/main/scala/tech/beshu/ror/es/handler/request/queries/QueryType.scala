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

import org.elasticsearch.index.query.*

import scala.jdk.CollectionConverters.*

sealed trait QueryType[QUERY <: QueryBuilder]

object QueryType {

  trait Compound[QUERY <: QueryBuilder] extends QueryType[QUERY] {
    def innerQueriesOf(query: QUERY): List[QueryBuilder]
  }

  object Compound {

    def apply[QUERY <: QueryBuilder](implicit ev: Compound[QUERY]) = ev

    implicit class Ops[QUERY <: QueryBuilder : Compound](val query: QUERY) {
      def innerQueries = Compound[QUERY].innerQueriesOf(query)
    }

    def withInnerQueries[QUERY <: QueryBuilder](f: QUERY => List[QueryBuilder]) = new Compound[QUERY] {
      override def innerQueriesOf(query: QUERY): List[QueryBuilder] = f(query)
    }

    def oneInnerQuery[QUERY <: QueryBuilder](f: QUERY => QueryBuilder) = new Compound[QUERY] {
      override def innerQueriesOf(query: QUERY): List[QueryBuilder] = List(f(query))
    }
  }

  trait Leaf[QUERY <: QueryBuilder] extends QueryType[QUERY]

  object instances {

    implicit val boolQueryType: Compound[BoolQueryBuilder] = Compound.withInnerQueries { query =>
      List(query.must(), query.mustNot(), query.filter(), query.should())
        .flatMap(_.asScala.toList)
    }

    implicit val boostingQueryType: Compound[BoostingQueryBuilder] = Compound.withInnerQueries { query =>
      List(query.negativeQuery(), query.positiveQuery())
    }

    implicit val disMaxQueryType: Compound[DisMaxQueryBuilder] = Compound.withInnerQueries { query =>
      query.innerQueries()
        .asScala
        .toList
    }

    implicit val constantScoreQueryType: Compound[ConstantScoreQueryBuilder] = Compound.oneInnerQuery(_.innerQuery())

    implicit object ExistsQueryType extends Leaf[ExistsQueryBuilder]
    implicit object FuzzyQueryType extends Leaf[FuzzyQueryBuilder]
    implicit object PrefixQueryType extends Leaf[PrefixQueryBuilder]
    implicit object RangeQueryType extends Leaf[RangeQueryBuilder]
    implicit object RegexpQueryType extends Leaf[RegexpQueryBuilder]
    implicit object TermQueryType extends Leaf[TermQueryBuilder]
    implicit object TermsSetQueryType extends Leaf[TermsSetQueryBuilder]
    implicit object WildcardQueryType extends Leaf[WildcardQueryBuilder]

    implicit object MatchBoolPrefixQueryType extends Leaf[MatchBoolPrefixQueryBuilder]
    implicit object MatchQueryType extends Leaf[MatchQueryBuilder]
    implicit object MatchPhraseQueryType extends Leaf[MatchPhraseQueryBuilder]
    implicit object MatchPhrasePrefixQueryType extends Leaf[MatchPhrasePrefixQueryBuilder]
  }
}