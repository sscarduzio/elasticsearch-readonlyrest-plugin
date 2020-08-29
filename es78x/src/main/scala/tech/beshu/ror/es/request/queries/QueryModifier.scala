package tech.beshu.ror.es.request.queries

import org.elasticsearch.index.query._
import tech.beshu.ror.accesscontrol.domain.FieldsRestrictions
import tech.beshu.ror.es.request.queries.QueryModifier.QueryModificationEligibility.{ModificationImpossible, ModificationPossible}

object QueryModifier {

  def modifyForFLS(queryBuilder: QueryBuilder,
                   fieldsRestrictions: FieldsRestrictions): QueryBuilder = {
    resolveModificationEligibility(queryBuilder) match {
      case ModificationPossible(helper, builder) => helper.modifyUsing(builder, fieldsRestrictions)
      case ModificationImpossible => queryBuilder
    }
  }

  private def resolveModificationEligibility(queryBuilder: QueryBuilder): QueryModificationEligibility = queryBuilder match {
    case builder: TermQueryBuilder =>
      ModificationPossible(new TermQueryFLSHelper(), builder)
    case builder: MatchQueryBuilder =>
      ModificationPossible(new MatchQueryFLSHelper(), builder)
    case builder: BoolQueryBuilder =>
      ModificationPossible(new BoolQueryFLSHelper(), builder)
    case _ => ModificationImpossible
  }

  sealed trait QueryModificationEligibility
  object QueryModificationEligibility {

    final case class ModificationPossible[BUILDER <: QueryBuilder](helper: QueryFLSHelper[BUILDER],
                                                                   builder: BUILDER) extends QueryModificationEligibility

    case object ModificationImpossible extends QueryModificationEligibility
  }
}
