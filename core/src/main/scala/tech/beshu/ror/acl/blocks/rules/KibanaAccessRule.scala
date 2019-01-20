package tech.beshu.ror.acl.blocks.rules

import java.util.regex.Pattern

import cats.implicits._
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.commons.Constants
import tech.beshu.ror.acl.aDomain.Header.Name.kibanaAccess
import tech.beshu.ror.acl.aDomain.IndexName.devNullKibana
import tech.beshu.ror.acl.aDomain.KibanaAccess.{RO, ROStrict, RW}
import tech.beshu.ror.acl.aDomain.KibanaAccess._
import tech.beshu.ror.acl.aDomain._
import tech.beshu.ror.commons.utils.MatcherWithWildcards
import tech.beshu.ror.acl.blocks.rules.KibanaAccessRule._
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.{BlockContext, Value}
import tech.beshu.ror.acl.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.acl.blocks.rules.utils.{MatcherWithWildcardsJavaAdapter, StringTNaturalTransformation}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.headerValues._
import tech.beshu.ror.acl.show.logs._
import scala.util.Try

class KibanaAccessRule(val settings: Settings)
  extends RegularRule with Logging {

  import KibanaAccessRule.stringActionNT

  override val name: Rule.Name = KibanaAccessRule.name

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
    if (kibanaCanBeModified && isTargetingKibana(requestContext, kibanaIndex)) {
      if (Matchers.roMatcher.`match`(requestContext.action) ||
        Matchers.rwMatcher.`match`(requestContext.action) ||
        requestContext.action.hasPrefix("indices:data/write")) {
        logger.debug(s"RW access to Kibana index: ${requestContext.id.show}")
        Fulfilled(modifyMatched(blockContext, Some(kibanaIndex)))
      } else {
        logger.info(s"RW access to Kibana, but unrecognized action ${requestContext.action.show} reqID: ${requestContext.id.show}")
        Rejected
      }
    } else if (isReadonlyrestAdmin(requestContext)) {
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
    def applyKibanaAccessHeader = (bc: BlockContext) => {
      if (settings.kibanaMetadataEnabled) bc.withAddedResponseHeader(Header(kibanaAccess, settings.access))
      else bc
    }
    def applyKibanaIndex = (bc: BlockContext) => {
      kibanaIndex match {
        case Some(index) => bc.withKibanaIndex(index)
        case None => bc
      }
    }
    (applyKibanaAccessHeader :: applyKibanaIndex :: Nil).reduceLeft(_ andThen _).apply(blockContext)
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
    val roMatcher = new MatcherWithWildcardsJavaAdapter(new MatcherWithWildcards(Constants.RO_ACTIONS))
    val rwMatcher = new MatcherWithWildcardsJavaAdapter(new MatcherWithWildcards(Constants.RW_ACTIONS))
    val adminMatcher = new MatcherWithWildcardsJavaAdapter(new MatcherWithWildcards(Constants.ADMIN_ACTIONS))
    val clusterMatcher = new MatcherWithWildcardsJavaAdapter(new MatcherWithWildcards(Constants.CLUSTER_ACTIONS))
  }

  private implicit val stringActionNT: StringTNaturalTransformation[Action] =
    StringTNaturalTransformation[Action](Action.apply, _.value)
}
