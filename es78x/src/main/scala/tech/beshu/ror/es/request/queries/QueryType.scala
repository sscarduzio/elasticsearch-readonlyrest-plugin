package tech.beshu.ror.es.request.queries

import org.elasticsearch.index.query.{BoolQueryBuilder, BoostingQueryBuilder, CommonTermsQueryBuilder, ConstantScoreQueryBuilder, DisMaxQueryBuilder, ExistsQueryBuilder, FuzzyQueryBuilder, MatchBoolPrefixQueryBuilder, MatchPhrasePrefixQueryBuilder, MatchPhraseQueryBuilder, MatchQueryBuilder, PrefixQueryBuilder, QueryBuilder, RangeQueryBuilder, RegexpQueryBuilder, TermQueryBuilder, TermsSetQueryBuilder, WildcardQueryBuilder}

import scala.collection.JavaConverters._

sealed trait QueryType[QUERY <: QueryBuilder]
object QueryType {

  trait Compound[QUERY <: QueryBuilder] extends QueryType[QUERY] {
    def innerQueriesOf(query: QUERY): List[QueryBuilder]
  }

  object Compound {
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

    implicit object CommonTermsQueryType extends Leaf[CommonTermsQueryBuilder]
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