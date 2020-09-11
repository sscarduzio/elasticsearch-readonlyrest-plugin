package tech.beshu.ror.es.request.queries

import org.elasticsearch.index.query._
import tech.beshu.ror.accesscontrol.fls.FLS.Strategy
import tech.beshu.ror.accesscontrol.fls.FLS.Strategy.BasedOnESRequestContext.QuerySpecified.UsedField
import tech.beshu.ror.accesscontrol.fls.FLS.Strategy.{BasedOnESRequestContext, LuceneLowLevelApproach}
import tech.beshu.ror.es.request.queries.QueryType.Compound
import tech.beshu.ror.es.request.queries.QueryType.instances._
import tech.beshu.ror.es.request.queries.ops.FieldsUsage.NotUsingFields
import tech.beshu.ror.es.request.queries.ops.FieldsUsage.UsingFields.{CantExtractFields, FieldsExtractable}
import tech.beshu.ror.fls.FieldsPolicy
import tech.beshu.ror.utils.ReflecUtils.invokeMethodCached

object ops {

  def hasWildcard(fieldName: String): Boolean = fieldName.contains("*")

  sealed trait FieldsUsage

  object FieldsUsage {
    case object NotUsingFields extends FieldsUsage
    sealed trait UsingFields extends FieldsUsage

    object UsingFields {
      case object CantExtractFields extends UsingFields
      final case class FieldsExtractable(usedFields: List[UsedField]) extends UsingFields

      object FieldsExtractable {
        def one(field: String): FieldsExtractable = FieldsExtractable(List(UsedField(field)))
      }
    }
  }

  def resolveFLSStrategy(fieldsPolicy: FieldsPolicy)
                        (queryBuilder: QueryBuilder): Strategy = {
    resolveFieldsUsageIn(queryBuilder) match {
      case FieldsUsage.NotUsingFields =>
        BasedOnESRequestContext.QuerySpecified.NoFieldsInQuery
      case CantExtractFields =>
        LuceneLowLevelApproach
      case fieldsExtractable: FieldsExtractable =>
        BasedOnESRequestContext.QuerySpecified.QueryWithFields()
    }
  }

  def resolveFieldsUsageIn(queryBuilder: QueryBuilder): FieldsUsage = queryBuilder match {
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
      case None => CantExtractFields
    }
    case builder: WildcardQueryBuilder => FieldsExtractable.one(builder.fieldName())

    case _ =>
      CantExtractFields
  }

  def resolveFieldsUsageForCompoundQuery[QUERY <: QueryBuilder : Compound](compoundQuery: QUERY): FieldsUsage = {
    val innerQueries = implicitly[Compound[QUERY]].innerQueriesOf(compoundQuery)
    val innerQueriesFieldsExtractability = innerQueries.map(resolveFieldsUsageIn)
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
