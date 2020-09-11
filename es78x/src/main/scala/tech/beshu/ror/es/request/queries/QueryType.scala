package tech.beshu.ror.es.request.queries

import org.elasticsearch.index.query.{BoolQueryBuilder, BoostingQueryBuilder, ConstantScoreQueryBuilder, DisMaxQueryBuilder, QueryBuilder}

import scala.collection.JavaConverters._

sealed trait QueryType[QUERY <: QueryBuilder]
object QueryType {
  trait Compound[QUERY <: QueryBuilder] extends QueryType[QUERY] {
    def innerQueriesOf(query: QUERY): List[QueryBuilder]
  }
  trait Leaf extends QueryType[Nothing]

  object instances {

    implicit object BoolQueryType extends Compound[BoolQueryBuilder] {
      override def innerQueriesOf(query: BoolQueryBuilder): List[QueryBuilder] = {
        List(query.must(), query.mustNot(), query.filter(), query.should())
          .flatMap(_.asScala.toList)
      }
    }

    implicit object BoostingQueryType extends Compound[BoostingQueryBuilder] {
      override def innerQueriesOf(query: BoostingQueryBuilder): List[QueryBuilder] = {
        List(query.negativeQuery(), query.positiveQuery())
      }
    }

    implicit object ConstantScoreQueryType extends Compound[ConstantScoreQueryBuilder] {
      override def innerQueriesOf(query: ConstantScoreQueryBuilder): List[QueryBuilder] = {
        List(query.innerQuery())
      }
    }

    implicit object DisMaxQueryType extends Compound[DisMaxQueryBuilder] {
      override def innerQueriesOf(query: DisMaxQueryBuilder): List[QueryBuilder] = {
        query.innerQueries()
          .asScala
          .toList
      }
    }

    implicit object TermQueryType extends QueryType.Leaf
  }
}