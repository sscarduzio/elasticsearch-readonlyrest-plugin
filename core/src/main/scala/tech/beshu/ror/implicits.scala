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
package tech.beshu.ror

import better.files.File
import cats.Show
import cats.data.NonEmptyList
import cats.implicits.*
import eu.timepit.refined.api.*
import eu.timepit.refined.types.string.NonEmptyString
import io.lemonlabs.uri.Uri
import squants.information.Information
import tech.beshu.ror.accesscontrol.blocks.*
import tech.beshu.ror.accesscontrol.blocks.Block.HistoryItem.RuleHistoryItem
import tech.beshu.ror.accesscontrol.blocks.Block.Policy.{Allow, Forbid}
import tech.beshu.ror.accesscontrol.blocks.Block.{History, Name, Policy}
import tech.beshu.ror.accesscontrol.blocks.definitions.*
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.LdapConnectionConfig.*
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UserGroupsSearchFilterConfig.UserGroupsSearchMode.*
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.{Dn, LdapService}
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RuleName, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.{ActionsRule, FieldsRule, FilterRule, ResponseFieldsRule}
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.*
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Unresolvable
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.VariableContext.UsageRequirement.*
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.VariableContext.VariableType
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.{RuntimeResolvableVariableCreator, VariableContext}
import tech.beshu.ror.accesscontrol.blocks.variables.startup.StartupResolvableVariableCreator
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.domain.*
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.AccessRequirement.{MustBeAbsent, MustBePresent}
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Remote.ClusterName
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.Strategy
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.Header.AuthorizationValueError
import tech.beshu.ror.accesscontrol.domain.KibanaAllowedApiPath.AllowedHttpMethod
import tech.beshu.ror.accesscontrol.domain.KibanaAllowedApiPath.AllowedHttpMethod.HttpMethod
import tech.beshu.ror.accesscontrol.domain.ResponseFieldsFiltering.AccessMode.{Blacklist, Whitelist}
import tech.beshu.ror.accesscontrol.domain.ResponseFieldsFiltering.ResponseFieldsRestrictions
import tech.beshu.ror.accesscontrol.domain.User.UserIdPattern
import tech.beshu.ror.accesscontrol.factory.BlockValidator.BlockValidationError
import tech.beshu.ror.accesscontrol.factory.BlockValidator.BlockValidationError.{KibanaRuleTogetherWith, KibanaUserDataRuleTogetherWith}
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory.HttpClient
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.boot.ReadonlyRest.StartingFailure
import tech.beshu.ror.providers.EnvVarProvider.EnvVarName
import tech.beshu.ror.providers.PropertiesProvider.PropName
import tech.beshu.ror.settings.es.LoadingRorCoreStrategySettings.CoreRefreshSettings
import tech.beshu.ror.settings.es.LoadingRorCoreStrategySettings.LoadingRetryStrategySettings.{LoadingAttemptsCount, LoadingAttemptsInterval, LoadingDelay}
import tech.beshu.ror.settings.es.YamlFileBasedSettingsLoader
import tech.beshu.ror.settings.ror.RawRorSettingsYamlParser.ParsingRorSettingsError
import tech.beshu.ror.settings.ror.RawRorSettingsYamlParser.ParsingRorSettingsError.{InvalidContent, MoreThanOneRorSection, NoRorSection}
import tech.beshu.ror.settings.ror.source.ReadOnlySettingsSource.LoadingSettingsError
import tech.beshu.ror.settings.ror.source.ReadWriteSettingsSource.SavingSettingsError
import tech.beshu.ror.settings.ror.source.{FileSettingsSource, IndexSettingsSource}
import tech.beshu.ror.settings.ror.{MainRorSettings, TestRorSettings}
import tech.beshu.ror.utils.ScalaOps.*
import tech.beshu.ror.utils.json.JsonPath
import tech.beshu.ror.utils.set.CovariantSet
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import java.io.File as JFile
import java.nio.file.Path as JPath
import java.time.Instant
import scala.collection.immutable.SortedSet
import scala.concurrent.duration.FiniteDuration
import scala.language.{implicitConversions, postfixOps}

object implicits
  extends LogsShowInstances
    with cats.syntax.AllSyntax

trait LogsShowInstances
  extends cats.instances.AllInstances {

  override implicit def catsStdShowForSet[A](implicit evidence$3: Show[A]): Show[Set[A]] = Show.show(iterableLikeShow.show)

  override implicit def catsStdShowForList[A: Show]: Show[List[A]] = Show.show(iterableLikeShow.show)

  override implicit def catsStdShowForSortedSet[A: Show]: Show[SortedSet[A]] = Show.show(iterableLikeShow.show)

  implicit val nonEmptyStringShow: Show[NonEmptyString] = Show.show(_.value)

  implicit def covariantSetShow[T: Show]: Show[CovariantSet[T]] = Show.show(iterableLikeShow.show)

  implicit def uniqueNonEmptyListShow[T: Show]: Show[UniqueNonEmptyList[T]] = Show.show(_.toList.show)

  implicit def iterableLikeShow[T: Show, I <: Iterable[T]]: Show[I] = Show.show(_.map(_.show).mkString(", "))

  implicit def nonEmptyListShow[T: Show]: Show[NonEmptyList[T]] = Show.show(_.toList.show)

  implicit def patternShow[T: Show]: Show[Pattern[T]] = Show.show(_.value.show)

  implicit def refinedString[T]: Show[String Refined T] = Show.show(_.value)

  implicit def refinedFiniteDurationShow[T]: Show[FiniteDuration Refined T] = Show.show(_.value.toString)

  implicit def classShow[T]: Show[Class[T]] = Show.show(_.getSimpleName)

  implicit val instantShow: Show[Instant] = Show.show(_.toString)

  implicit val requestIdShow: Show[RequestId] = Show.show(_.value)
  implicit val userIdShow: Show[User.Id] = Show.show(_.value.value)
  implicit val userIdPatternShow: Show[UserIdPattern] = Show.show(_.value.show)
  implicit val userIdPatternsShow: Show[UserIdPatterns] = Show.show(_.patterns.show)
  implicit val loggedUserShow: Show[LoggedUser] = Show.show(_.id.value.value)
  implicit val typeShow: Show[Type] = Show.show(_.value)
  implicit val actionShow: Show[Action] = Show.show(_.value)
  implicit val addressShow: Show[Address] = Show.show {
    case Address.Ip(value) => value.toString
    case Address.Name(value) => value.toString
  }
  implicit val informationShow: Show[Information] = Show.show { i => i.toString }
  implicit val requestContextIdShow: Show[RequestContext.Id] = Show.show(_.value)
  implicit val methodShow: Show[RequestContext.Method] = Show.show(_.value)
  implicit val jPathShow: Show[JPath] = Show.show(_.toString)
  implicit val jFilePathShow: Show[JFile] = Show.show(_.toString)
  implicit val fileShow: Show[File] = Show.show(_.toString)
  implicit val jsonPathShow: Show[JsonPath] = Show.show(_.rawPath)
  implicit val uriShow: Show[Uri] = Show.show(_.toJavaURI.toString())
  implicit val headerNameShow: Show[Header.Name] = Show.show(_.value.value)
  implicit val kibanaAppShow: Show[KibanaApp] = Show.show {
    case KibanaApp.FullNameKibanaApp(name) => name.value
    case KibanaApp.KibanaAppRegex(regex) => regex.value.value
  }
  implicit val kibanaAllowedApiPathShow: Show[KibanaAllowedApiPath] = Show.show { p =>
    val httpMethodStr = p.httpMethod match {
      case AllowedHttpMethod.Any => "*"
      case AllowedHttpMethod.Specific(HttpMethod.Get) => "GET"
      case AllowedHttpMethod.Specific(HttpMethod.Put) => "PUT"
      case AllowedHttpMethod.Specific(HttpMethod.Post) => "POST"
      case AllowedHttpMethod.Specific(HttpMethod.Delete) => "DELETE"
    }
    s"$httpMethodStr:${p.pathRegex.pattern.pattern()}"
  }
  implicit val proxyAuthNameShow: Show[ProxyAuth.Name] = Show.show(_.value)

  implicit def requestedIndexShow[T <: ClusterIndexName : Show]: Show[RequestedIndex[T]] = Show(_.name.show)

  implicit val clusterIndexNameShow: Show[ClusterIndexName] = Show.show(_.stringify)
  implicit val localClusterIndexNameShow: Show[ClusterIndexName.Local] = Show.show(_.stringify)
  implicit val remoteClusterIndexNameShow: Show[ClusterIndexName.Remote] = Show.show(_.stringify)
  implicit val clusterNameFullShow: Show[ClusterName.Full] = Show.show(_.value.value)
  implicit val indexNameShow: Show[IndexName] = Show.show {
    case f@IndexName.Full(_) => f.show
    case IndexName.Pattern(namePattern) => namePattern.value
  }
  implicit val kibanaIndexNameShow: Show[KibanaIndexName] = Show.show(_.underlying.show)
  implicit val fullIndexNameShow: Show[IndexName.Full] = Show.show(_.name.value)
  implicit val indexPatternShow: Show[IndexPattern] = Show.show(_.value.show)
  implicit val fullDataStreamShow: Show[DataStreamName.Full] = Show.show(_.value.value)
  implicit val aliasPlaceholderShow: Show[AliasPlaceholder] = Show.show(_.alias.show)
  implicit val externalAuthenticationServiceNameShow: Show[ExternalAuthenticationService.Name] = Show.show(_.value.value)
  implicit val groupIdShow: Show[GroupId] = Show.show(_.value.value)
  implicit val groupShow: Show[Group] = Show.show(group => s"(id=${group.id.show},name=${group.name.value.value})")
  implicit val tokenShow: Show[AuthorizationToken] = Show.show(_.value.value)
  implicit val jwtTokenShow: Show[Jwt.Token] = Show.show(_.value.value)
  implicit val uriPathShow: Show[UriPath] = Show.show(_.value.value)
  implicit val dnShow: Show[Dn] = Show.show(_.value.value)
  implicit val showGroupSearchFilter: Show[GroupSearchFilter] = Show.show(_.value.value)
  implicit val showGroupIdAttribute: Show[GroupIdAttribute] = Show.show(_.value.value)
  implicit val showUniqueMemberAttribute: Show[UniqueMemberAttribute] = Show.show(_.value.value)
  implicit val showGroupsFromUserAttribute: Show[GroupsFromUserAttribute] = Show.show(_.value.value)
  implicit val envNameShow: Show[EnvVarName] = Show.show(_.value.value)
  implicit val propNameShow: Show[PropName] = Show.show(_.value.value)
  implicit val templateNameShow: Show[TemplateName] = Show.show(_.value.value)
  implicit val templateNamePatternShow: Show[TemplateNamePattern] = Show.show(_.value.value)
  implicit val templateShow: Show[Template] = Show.show {
    case Template.IndexTemplate(name, patterns, aliases) => s"IndexTemplate[name=[${name.show}],patterns=[${patterns.show}],aliases=[${aliases.show}]]"
    case Template.LegacyTemplate(name, patterns, aliases) => s"LegacyTemplate[name=[${name.show}],patterns=[${patterns.show}],aliases=[${aliases.show}]]"
    case Template.ComponentTemplate(name, aliases) => s"ComponentTemplate[name=[${name.show}],aliases=[${aliases.show}]]"
  }
  implicit val snapshotNameShow: Show[SnapshotName] = Show.show(v => SnapshotName.toString(v))
  implicit val ldapHostShow: Show[LdapHost] = Show.show(_.url.toString())
  implicit val ldapServiceNameShow: Show[LdapService.Name] = Show.show(_.value.value)
  implicit val externalAuthorizationServiceNameShow: Show[ExternalAuthorizationService.Name] = Show.show(_.value.value)
  implicit val jwtDefNameShow: Show[JwtDef.Name] = Show.show(_.value.value)
  implicit val rorKbnDefNameShow: Show[RorKbnDef.Name] = Show.show(_.value.value)
  implicit val httpRequestShow: Show[HttpClient.Request] = Show.show(_.toString)
  implicit val httpResponseShow: Show[HttpClient.Response] = Show.show(_.toString)
  implicit val functionNameShow: Show[FunctionName] = Show.show(_.name.value)
  implicit val functionDefinitionShow: Show[FunctionDefinition] = Show.show(_.functionName.show)

  implicit def ruleNameShow[T <: RuleName[_]]: Show[T] = Show.show(_.name.value)

  implicit def blockContextShow[B <: BlockContext](implicit showHeader: Show[Header]): Show[B] =
    Show.show { bc =>
      (showOption("user", bc.userMetadata.loggedUser) ::
        showOption("group", bc.userMetadata.currentGroupId) ::
        showNamedIterable("av_groups", bc.userMetadata.availableGroups.toList.map(_.id)) ::
        showNamedIterable("indices", bc.indices) :: // todo: for sure it's ok?
        showOption("kibana_idx", bc.userMetadata.kibanaIndex) ::
        showOption("fls", bc.fieldLevelSecurity) ::
        showNamedIterable("response_hdr", bc.responseHeaders) ::
        showNamedIterable("repositories", bc.repositories) ::
        showNamedIterable("snapshots", bc.snapshots) ::
        showNamedIterable("response_transformations", bc.responseTransformations) ::
        showOption("template", bc.templateOperation) ::
        Nil flatten) mkString ";"
    }

  private implicit val responseTransformation: Show[ResponseTransformation] = Show.show {
    case FilteredResponseFields(ResponseFieldsRestrictions(fields, mode)) =>
      val fieldPrefix = mode match {
        case Whitelist => ""
        case Blacklist => "~"
      }
      val commaSeparatedFields = fields.map(fieldPrefix + _.value.value).toList.mkString(",")
      s"fields=[${commaSeparatedFields.show}]"
  }

  private implicit val kibanaAccessShow: Show[KibanaAccess] = Show {
    case KibanaAccess.RO => "ro"
    case KibanaAccess.ROStrict => "ro_strict"
    case KibanaAccess.RW => "rw"
    case KibanaAccess.Admin => "admin"
    case KibanaAccess.ApiOnly => "api_only"
    case KibanaAccess.Unrestricted => "unrestricted"
  }
  private implicit val userOriginShow: Show[UserOrigin] = Show.show(_.value.value)
  implicit val userMetadataShow: Show[UserMetadata] = Show.show { u =>
    (showOption("user", u.loggedUser) ::
      showOption("curr_group", u.currentGroupId) ::
      showNamedIterable("av_groups", u.availableGroups.toList.map(_.id)) ::
      showOption("kibana_idx", u.kibanaIndex) ::
      showNamedIterable("hidden_apps", u.hiddenKibanaApps) ::
      showNamedIterable("allowed_api_paths", u.allowedKibanaApiPaths) ::
      showOption("kibana_access", u.kibanaAccess) ::
      showOption("user_origin", u.userOrigin) ::
      Nil flatten) mkString ";"
  }

  implicit val flsStrategyShow: Show[FieldLevelSecurity] = Show.show[FieldLevelSecurity] { fls =>
    fls.strategy match {
      case Strategy.FlsAtLuceneLevelApproach => "[strategy: fls_at_lucene_level]"
      case Strategy.BasedOnBlockContextOnly.EverythingAllowed => "[strategy: fls_at_es_level]"
      case Strategy.BasedOnBlockContextOnly.NotAllowedFieldsUsed(fields) => s"[strategy: fls_at_es_level, ${showNamedNonEmptyList("not_allowed_fields_used", fields)}]"
    }
  }

  implicit val templateOperationShow: Show[TemplateOperation] = Show.show {
    case TemplateOperation.GettingLegacyAndIndexTemplates(op1, op2) =>
      s"GETALL(${op1.namePatterns.show}|${op2.namePatterns.show})"
    case TemplateOperation.GettingLegacyTemplates(namePatterns) =>
      s"GET(${namePatterns.show})"
    case TemplateOperation.AddingLegacyTemplate(name, patterns, aliases) =>
      s"ADD(${name.show}:${patterns.show}:${aliases.show})"
    case TemplateOperation.DeletingLegacyTemplates(namePatterns) =>
      s"DEL(${namePatterns.show})"
    case TemplateOperation.GettingIndexTemplates(namePatterns) =>
      s"GET(${namePatterns.show})"
    case TemplateOperation.AddingIndexTemplate(name, patterns, aliases) =>
      s"ADD(${name.show}:${patterns.show}:${aliases.show})"
    case TemplateOperation.AddingIndexTemplateAndGetAllowedOnes(name, patterns, aliases, allowedTemplates) =>
      s"ADDGET(${name.show}:${patterns.show}:${aliases.show}:${allowedTemplates.show})"
    case TemplateOperation.DeletingIndexTemplates(namePatterns) =>
      s"DEL(${namePatterns.show})"
    case TemplateOperation.GettingComponentTemplates(namePatterns) =>
      s"GET(${namePatterns.show})"
    case TemplateOperation.AddingComponentTemplate(name, aliases) =>
      s"ADD(${name.show}:${aliases.show})"
    case TemplateOperation.DeletingComponentTemplates(namePatterns) =>
      s"DEL(${namePatterns.show})"
  }

  implicit val specificFieldShow: Show[FieldLevelSecurity.RequestFieldsUsage.UsedField.SpecificField] = Show.show(_.value)
  implicit val blockNameShow: Show[Name] = Show.show(_.value)

  implicit def ruleHistoryItemShow[B <: BlockContext]: Show[RuleHistoryItem[B]] = Show.show { hi =>
    s"${hi.rule.show}->${
      hi.result match {
        case RuleResult.Fulfilled(_) => "true"
        case RuleResult.Rejected(_) => "false"
      }
    }"
  }

  implicit def historyShow[B <: BlockContext](implicit headerShow: Show[Header]): Show[History[B]] =
    Show.show[History[B]] { h =>
      val rulesHistoryItemsStr = h.items
        .collect { case hi: RuleHistoryItem[B] => hi }
        .map(_.show)
        .mkStringOrEmptyString(" RULES:[", ", ", "]")
      val resolvedPart = h.blockContext.show match {
        case "" => ""
        case nonEmpty => s" RESOLVED:[$nonEmpty]"
      }
      s"""[${h.block.show}->${rulesHistoryItemsStr.show}${resolvedPart.show}]"""
    }

  implicit val policyShow: Show[Policy] = Show.show {
    case Allow => "ALLOW"
    case Forbid(_) => "FORBID"
  }
  implicit val blockShow: Show[Block] = Show.show { b =>
    s"{ name: '${b.name.show}', policy: ${b.policy.show}, rules: [${b.rules.toList.map(_.name).show}]"
  }
  implicit val runtimeResolvableVariableCreationErrorShow: Show[RuntimeResolvableVariableCreator.CreationError] = Show.show {
    case RuntimeResolvableVariableCreator.CreationError.CannotUserMultiVariableInSingleVariableContext =>
      "Cannot use multi value variable in non-array context"
    case RuntimeResolvableVariableCreator.CreationError.OnlyOneMultiVariableCanBeUsedInVariableDefinition =>
      "Cannot use more than one multi-value variable"
    case RuntimeResolvableVariableCreator.CreationError.InvalidVariableDefinition(cause) =>
      s"Variable malformed, cause: ${cause.show}"
    case RuntimeResolvableVariableCreator.CreationError.VariableConversionError(cause) =>
      cause
  }
  implicit val startupResolvableVariableCreationErrorShow: Show[StartupResolvableVariableCreator.CreationError] = Show.show {
    case StartupResolvableVariableCreator.CreationError.CannotUserMultiVariableInSingleVariableContext =>
      "Cannot use multi value variable in non-array context"
    case StartupResolvableVariableCreator.CreationError.OnlyOneMultiVariableCanBeUsedInVariableDefinition =>
      "Cannot use more than one multi-value variable"
    case StartupResolvableVariableCreator.CreationError.InvalidVariableDefinition(cause) =>
      s"Variable malformed, cause: ${cause.show}"
  }
  implicit val variableTypeShow: Show[VariableContext.VariableType] = Show.show {
    case _: VariableType.User => "user"
    case _: VariableType.CurrentGroup => "current group"
    case _: VariableType.AvailableGroups => "available groups"
    case _: VariableType.Header => "header"
    case _: VariableType.Jwt => "JWT"
  }

  implicit val complianceResultShow: Show[ComplianceResult.NonCompliantWith] = Show.show {
    case ComplianceResult.NonCompliantWith(OneOfRuleBeforeMustBeAuthenticationRule(variableType)) =>
      s"Variable used to extract ${variableType.show} requires one of the rules defined in block to be authentication rule"
    case ComplianceResult.NonCompliantWith(JwtVariableIsAllowedOnlyWhenAuthRuleRelatedToJwtTokenIsProcessedEarlier) =>
      s"JWT variables are not allowed to be used in Groups rule"
  }

  def obfuscatedHeaderShow(obfuscatedHeaders: Iterable[Header.Name]): Show[Header] = {
    Show.show[Header] {
      case Header(name, _) if obfuscatedHeaders.exists(_ === name) => s"${name.show}=<OMITTED>"
      case header => headerShow.show(header)
    }
  }

  val headerShow: Show[Header] = Show.show { case Header(name, value) => s"${name.show}=${value.value.show}" }

  def blockValidationErrorShow(block: Block.Name): Show[BlockValidationError] = Show.show {
    case BlockValidationError.AuthorizationWithoutAuthentication =>
      s"The '${block.show}' block contains an authorization rule, but not an authentication rule. This does not mean anything if you don't also set some authentication rule."
    case BlockValidationError.OnlyOneAuthenticationRuleAllowed(authRules) =>
      s"The '${block.show}' block should contain only one authentication rule, but contains: [${authRules.map(_.name).toList.show}]"
    case BlockValidationError.RuleDoesNotMeetRequirement(complianceResult) =>
      s"The '${block.show}' block doesn't meet requirements for defined variables. ${complianceResult.show}"
    case error: BlockValidationError.KibanaRuleTogetherWith =>
      val conflictingRule = error match {
        case KibanaRuleTogetherWith.ActionsRule => ActionsRule.Name.name
        case KibanaRuleTogetherWith.FilterRule => FilterRule.Name.name
        case KibanaRuleTogetherWith.FieldsRule => FieldsRule.Name.name
        case KibanaRuleTogetherWith.ResponseFieldsRule => ResponseFieldsRule.Name.name
      }
      s"The '${block.show}' block contains '${KibanaUserDataRule.Name.name.show}' rule (or any deprecated kibana-related rule) and '${conflictingRule.show}' rule. These two cannot be used together in one block."
    case error: BlockValidationError.KibanaUserDataRuleTogetherWith =>
      val conflictingRule = error match {
        case KibanaUserDataRuleTogetherWith.KibanaAccessRule => KibanaAccessRule.Name.name
        case KibanaUserDataRuleTogetherWith.KibanaIndexRule => KibanaIndexRule.Name.name
        case KibanaUserDataRuleTogetherWith.KibanaTemplateIndexRule => KibanaTemplateIndexRule.Name.name
        case KibanaUserDataRuleTogetherWith.KibanaHideAppsRule => KibanaHideAppsRule.Name.name
      }
      s"The '${block.show}' block contains '${KibanaUserDataRule.Name.name.show}' rule and '${conflictingRule.show}' rule. The second one is deprecated. The first one offers all the second one is able to provide."
  }

  private def showNamedIterable[T: Show](name: String, iterable: Iterable[T]) = {
    if (iterable.isEmpty) None
    else Some(s"$name=${iterable.show}")
  }

  private def showNamedNonEmptyList[T: Show](name: String, nonEmptyList: NonEmptyList[T]) = {
    showNamedIterable(name, nonEmptyList.toList)
  }

  private def showOption[T: Show](name: String, option: Option[T]) = {
    showNamedIterable(name, option.toList)
  }

  val authorizationValueErrorWithDetailsShow: Show[AuthorizationValueError] = Show.show {
    case AuthorizationValueError.EmptyAuthorizationValue => "Empty authorization value"
    case AuthorizationValueError.InvalidHeaderFormat(value) => s"Unexpected header format in ror_metadata: [${value.show}]"
    case AuthorizationValueError.RorMetadataInvalidFormat(value, message) => s"Invalid format of ror_metadata: [${value.show}], reason: [${message.show}]"
  }

  val authorizationValueErrorSanitizedShow: Show[AuthorizationValueError] = Show.show {
    case AuthorizationValueError.EmptyAuthorizationValue => "Empty authorization value"
    case AuthorizationValueError.InvalidHeaderFormat(_) => s"Unexpected header format in ror_metadata"
    case AuthorizationValueError.RorMetadataInvalidFormat(_, message) => s"Invalid format of ror_metadata. Reason: [${message.show}]"
  }

  implicit val unresolvableErrorShow: Show[Unresolvable] = Show.show {
    case Unresolvable.CannotExtractValue(msg) => s"Cannot extract variable value. ${msg.show}"
    case Unresolvable.CannotInstantiateResolvedValue(msg) => s"Extracted value type doesn't fit. ${msg.show}"
  }

  implicit def accessShow[T: Show]: Show[AccessRequirement[T]] = Show.show {
    case MustBePresent(value) => value.show
    case MustBeAbsent(value) => s"~${value.show}"
  }

  implicit val coreRefreshSettingsShow: Show[CoreRefreshSettings] = Show.show {
    case CoreRefreshSettings.Disabled => "0 sec"
    case CoreRefreshSettings.Enabled(interval) => interval.value.toString()
  }

  implicit val esConfigFileShow: Show[EsConfigFile] = Show.show(_.file.show)

  implicit val loadingDelayShow: Show[LoadingDelay] = Show[FiniteDuration].contramap(_.value.value)

  implicit val loadingAttemptsCountShow: Show[LoadingAttemptsCount] = Show[Int].contramap(_.value.value)

  implicit val loadingAttemptsIntervalShow: Show[LoadingAttemptsInterval] = Show[FiniteDuration].contramap(_.value.value)

  implicit val testRorSettingsShow: Show[TestRorSettings] = Show.show(_.rawSettings.rawYaml)

  implicit val mainRorSettingsShow: Show[MainRorSettings] = Show.show(_.rawSettings.rawYaml)

  implicit val esConfigBasedRorSettingsLoadingErrorShow: Show[YamlFileBasedSettingsLoader.LoadingError] = Show.show {
    case YamlFileBasedSettingsLoader.LoadingError.FileNotFound(file) =>
      s"Cannot find settings file: [${file.show}]"
    case YamlFileBasedSettingsLoader.LoadingError.MalformedSettings(file, message) =>
      s"Settings file is malformed: [${file.show}], ${message.show}"
  }

  implicit val parsingRorSettingsErrorShow: Show[ParsingRorSettingsError] = Show.show {
    case NoRorSection => "Cannot find any 'readonlyrest' section in settings"
    case MoreThanOneRorSection => "Only one 'readonlyrest' section is required"
    case InvalidContent(ex) => s"Settings content is malformed. Details: ${ex.getMessage.show}"
  }

  implicit val indexSettingsSourceLoadingErrorShow: Show[IndexSettingsSource.LoadingError] = Show.show {
    case IndexSettingsSource.LoadingError.IndexNotFound => "Cannot find ReadonlyREST settings index"
    case IndexSettingsSource.LoadingError.DocumentNotFound => "Cannot found document with ReadonlyREST settings"
  }

  implicit val indexSettingsSourceSavingErrorShow: Show[IndexSettingsSource.SavingError] = Show.show {
    case IndexSettingsSource.SavingError.CannotSaveSettings => "Cannot save settings in the ReadonlyREST index"
  }

  implicit val fileSettingsSourceLoadingErrorShow: Show[FileSettingsSource.LoadingError] = Show.show {
    case FileSettingsSource.LoadingError.FileNotExist(file) => s"Cannot find settings file: ${file.pathAsString}"
  }

  implicit val startingFailureShow: Show[StartingFailure] = Show.show(_.message)

  implicit def loadingSettingsErrorShow[ERROR: Show]: Show[LoadingSettingsError[ERROR]] = Show.show {
    case LoadingSettingsError.SettingsMalformed(cause) => s"ROR settings are malformed: $cause"
    case LoadingSettingsError.SourceSpecificError(error) => implicitly[Show[ERROR]].show(error)
  }

  implicit def savingSettingsErrorShow[ERROR: Show]: Show[SavingSettingsError[ERROR]] = Show.show {
    case SavingSettingsError.SourceSpecificError(error) => implicitly[Show[ERROR]].show(error)
  }
}
