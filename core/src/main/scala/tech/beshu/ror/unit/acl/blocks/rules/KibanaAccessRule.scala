package tech.beshu.ror.unit.acl.blocks.rules

import java.util.regex.Pattern

import cats.implicits._
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.commons.aDomain.Header.Name.kibanaAccess
import tech.beshu.ror.commons.aDomain.IndexName.devNullKibana
import tech.beshu.ror.commons.aDomain.KibanaAccess.{RO, ROStrict, RW}
import tech.beshu.ror.commons.aDomain.KibanaAccess._
import tech.beshu.ror.commons.aDomain._
import tech.beshu.ror.commons.utils.MatcherWithWildcards
import tech.beshu.ror.unit.acl.blocks.rules.KibanaAccessRule.{name, _}
import tech.beshu.ror.unit.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.unit.acl.blocks.{BlockContext, Value}
import tech.beshu.ror.unit.acl.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.unit.acl.blocks.rules.utils.{MatcherWithWildcardsJavaAdapter, StringTNaturalTransformation}
import tech.beshu.ror.unit.acl.request.RequestContext
import tech.beshu.ror.commons.headerValues._
import tech.beshu.ror.commons.show.logs._

import scala.collection.JavaConverters._
import scala.util.Try

class KibanaAccessRule(val settings: Settings)
  extends RegularRule with Logging {

  import KibanaAccessRule.stringActionNT

  override val name: Rule.Name = name

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = Task.now {
    if (UriPath.fromUri(requestContext.uri) === UriPath.restMetadataPath) Fulfilled(modifyMatched(blockContext))
    // Allow other actions if devnull is targeted to readers and writers
    else if (requestContext.indices.contains(devNullKibana)) Fulfilled(modifyMatched(blockContext))
    // Any index, read op
    else if (Matchers.roMatcher.`match`(requestContext.action)) Fulfilled(modifyMatched(blockContext))
    else if (Matchers.clusterMatcher.`match`(requestContext.action)) Fulfilled(modifyMatched(blockContext))
    else if (isKibanaSimplaData(requestContext)) Fulfilled(modifyMatched(blockContext))
    else processCheck(requestContext, blockContext)
  }

  private def processCheck(requestContext: RequestContext, blockContext: BlockContext): RuleResult = {
    val kibanaIndex = settings
      .kibanaIndex
      .getValue(requestContext.variablesResolver, blockContext)
      .getOrElse(IndexName.kibana)

    // Save UI state in discover & Short urls
    kibanaIndexPattern(kibanaIndex) match {
      case None =>
        Rejected
      case Some(pattern) if isRoNonStrictCase(requestContext, kibanaIndex, pattern) =>
        Fulfilled(modifyMatched(blockContext, Some(kibanaIndex)))
      case Some(_) =>
        continueProcessing(requestContext, blockContext, kibanaIndex)
    }
  }

  private def continueProcessing(requestContext: RequestContext,
                                 blockContext: BlockContext,
                                 kibanaIndex: IndexName): RuleResult = {
    if(kibanaCanBeModified && isTargetingKibana(requestContext, kibanaIndex)) {
      if(Matchers.roMatcher.`match`(requestContext.action) ||
        Matchers.rwMatcher.`match`(requestContext.action) ||
        requestContext.action.hasPrefix("indices:data/write")) {
        logger.debug(s"RW access to Kibana index: ${requestContext.id.show}")
        Fulfilled(modifyMatched(blockContext, Some(kibanaIndex)))
      } else {
        logger.info(s"RW access to Kibana, but unrecognized action ${requestContext.action.show} reqID: ${requestContext.id.show}")
        Rejected
      }
    } else if(isReadonlyrestAdmin(requestContext)) {
      Fulfilled(modifyMatched(blockContext, Some(kibanaIndex)))
    } else {
      logger.debug(s"KIBANA ACCESS DENIED ${requestContext.id.show}")
      Rejected
    }
  }

  private def isReadonlyrestAdmin(requestContext: RequestContext) = {
    requestContext.indices.isEmpty || requestContext.indices.contains(IndexName.readonlyrest) &&
      settings.access === KibanaAccess.Admin &&
      Matchers.adminMatcher.`match`(requestContext.action)
  }

  private def isRoNonStrictCase(requestContext: RequestContext, kibanaIndex: IndexName, nonStrictAllowedPaths: Pattern) = {
    isTargetingKibana(requestContext, kibanaIndex) &&
      settings.access =!= ROStrict &&
      !kibanaCanBeModified &&
      nonStrictAllowedPaths.matcher(UriPath.fromUri(requestContext.uri).value).find() &&
      (requestContext.action.hasPrefix("indices:data/write/") || requestContext.action.hasPrefix("indices:admin/template/put"))
  }

  private def isKibanaSimplaData(requestContext: RequestContext) = {
    kibanaCanBeModified && requestContext.indices.size == 1 && requestContext.indices.head.hasPrefix("kibana_sample_data_")
  }

  private def isTargetingKibana(requestContext: RequestContext, kibanaIndex: IndexName) = {
    requestContext.indices.size == 1 && requestContext.indices.head === kibanaIndex
  }

  private def kibanaIndexPattern(kibanaIndex: IndexName) = {
    Try(Pattern.compile(
      "^/@kibana_index/(url|config/.*/_create|index-pattern|doc/index-pattern.*)/.*|^/_template/.*"
        .replace("@kibana_index", kibanaIndex.value)
    )).toOption
  }

  private def modifyMatched(blockContext: BlockContext, kibanaIndex: Option[IndexName] = None) = {
    kibanaIndex.foldLeft(blockContext.addResponseHeader(Header(kibanaAccess, settings.access))) {
      case (currentBlockContext, kibanaIndexName) => currentBlockContext.setKibanaIndex(kibanaIndexName)
    }
  }

  private val kibanaCanBeModified: Boolean = settings.access match {
    case RO | ROStrict => false
    case RW | Admin => true
  }
}

object KibanaAccessRule {
  val name = Rule.Name("kibana_access")

  final case class Settings(access: KibanaAccess, kibanaIndex: Value[IndexName], kibanaMetadataEnabled: Boolean)

  private object Matchers {
    val roMatcher = new MatcherWithWildcardsJavaAdapter(new MatcherWithWildcards(Set(
      "indices:admin/exists",
      "indices:admin/mappings/fields/get*",
      "indices:admin/mappings/get*",
      "indices:admin/validate/query",
      "indices:admin/get",
      "indices:admin/refresh*",
      "indices:data/read/*"
    ).asJava))

    val rwMatcher = new MatcherWithWildcardsJavaAdapter(new MatcherWithWildcards(Set(
      "indices:admin/create",
      "indices:admin/mapping/put",
      "indices:data/write/delete*",
      "indices:data/write/index",
      "indices:data/write/update*",
      "indices:data/write/bulk*",
      "indices:admin/template/*"
    ).asJava))

    val adminMatcher = new MatcherWithWildcardsJavaAdapter(new MatcherWithWildcards(Set(
      "cluster:admin/rradmin/*",
      "indices:data/write/*", // <-- DEPRECATED!
      "indices:admin/create"
    ).asJava))

    val clusterMatcher = new MatcherWithWildcardsJavaAdapter(new MatcherWithWildcards(Set(
      "cluster:monitor/nodes/info",
      "cluster:monitor/main",
      "cluster:monitor/health",
      "cluster:monitor/state",
      "cluster:monitor/xpack/*",
      "indices:admin/template/get*"
    ).asJava))
  }

  private implicit val stringActionNT: StringTNaturalTransformation[Action] =
    StringTNaturalTransformation[Action](Action.apply, _.value)
}
