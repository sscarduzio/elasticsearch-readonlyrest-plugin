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
package tech.beshu.ror.accesscontrol

import cats.data.NonEmptyList
import cats.implicits._
import cats.{Order, Show}
import com.softwaremill.sttp.{Method, Uri}
import eu.timepit.refined.api.Validate
import eu.timepit.refined.numeric.Greater
import eu.timepit.refined.types.string.NonEmptyString
import io.lemonlabs.uri.{Uri => LemonUri}
import shapeless.Nat
import tech.beshu.ror.accesscontrol.AccessControl.ForbiddenCause
import tech.beshu.ror.accesscontrol.blocks.Block.HistoryItem.RuleHistoryItem
import tech.beshu.ror.accesscontrol.blocks.Block.Policy.{Allow, Forbid}
import tech.beshu.ror.accesscontrol.blocks.Block.{History, Name, Policy, RuleDefinition}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.Dn
import tech.beshu.ror.accesscontrol.blocks.definitions.{ExternalAuthenticationService, ProxyAuth, UserDef}
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RuleName, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.VariableContext.UsageRequirement._
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.VariableContext.VariableType
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.{RuntimeResolvableVariableCreator, VariableContext}
import tech.beshu.ror.accesscontrol.blocks.variables.startup.StartupResolvableVariableCreator
import tech.beshu.ror.accesscontrol.blocks._
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UserGroupsSearchFilterConfig.UserGroupsSearchMode.{GroupNameAttribute, GroupSearchFilter, GroupsFromUserAttribute, UniqueMemberAttribute}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.ActionsRule
import tech.beshu.ror.accesscontrol.blocks.rules.kibana._
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Unresolvable
import tech.beshu.ror.accesscontrol.domain.AccessRequirement.{MustBeAbsent, MustBePresent}
import tech.beshu.ror.accesscontrol.domain.Address.Ip
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Remote.ClusterName
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions.{AccessMode, DocumentField}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.{FieldsRestrictions, Strategy}
import tech.beshu.ror.accesscontrol.domain.GroupLike.GroupName
import tech.beshu.ror.accesscontrol.domain.Header.AuthorizationValueError
import tech.beshu.ror.accesscontrol.domain.KibanaAllowedApiPath.AllowedHttpMethod
import tech.beshu.ror.accesscontrol.domain.KibanaAllowedApiPath.AllowedHttpMethod.HttpMethod
import tech.beshu.ror.accesscontrol.domain.ResponseFieldsFiltering.AccessMode.{Blacklist, Whitelist}
import tech.beshu.ror.accesscontrol.domain.ResponseFieldsFiltering.ResponseFieldsRestrictions
import tech.beshu.ror.accesscontrol.domain.User.UserIdPattern
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.factory.BlockValidator.BlockValidationError
import tech.beshu.ror.accesscontrol.factory.BlockValidator.BlockValidationError.KibanaUserDataRuleTogetherWith
import tech.beshu.ror.accesscontrol.header.{FromHeaderValue, ToHeaderValue}
import tech.beshu.ror.com.jayway.jsonpath.JsonPath
import tech.beshu.ror.providers.EnvVarProvider.EnvVarName
import tech.beshu.ror.providers.PropertiesProvider.PropName
import tech.beshu.ror.utils.ScalaOps._
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import java.util.Base64
import java.util.regex.{Pattern => RegexPattern}
import scala.concurrent.duration.FiniteDuration
import scala.language.{implicitConversions, postfixOps}
import scala.util.Try

object header {

  class ToTuple(val header: Header) extends AnyVal {
    def toTuple: (String, String) = (header.name.value.value, header.value.value)
  }
  object ToTuple {
    implicit def toTuple(header: Header): ToTuple = new ToTuple(header)
  }

  trait ToHeaderValue[T] {
    def toRawValue(t: T): NonEmptyString
  }
  object ToHeaderValue {
    def apply[T](func: T => NonEmptyString): ToHeaderValue[T] = (t: T) => func(t)
  }

  trait FromHeaderValue[T] {
    def fromRawValue(value: NonEmptyString): Try[T]
  }
}

object orders {
  implicit val nonEmptyStringOrder: Order[NonEmptyString] = Order.by(_.value)
  implicit val headerNameOrder: Order[Header.Name] = Order.by(_.value.value)
  implicit val headerOrder: Order[Header] = Order.by(h => (h.name, h.value.value))
  implicit val addressOrder: Order[Address] = Order.by {
    case Address.Ip(value) => value.toString()
    case Address.Name(value) => value.toString
  }
  implicit val idPatternOrder: Order[UserIdPattern] = Order.by(_.value)
  implicit val methodOrder: Order[Method] = Order.by(_.m)
  implicit val apiKeyOrder: Order[ApiKey] = Order.by(_.value)
  implicit val kibanaAppOrder: Order[KibanaApp] = Order.by {
    case KibanaApp.FullNameKibanaApp(name) => name.value
    case KibanaApp.KibanaAppRegex(regex) => regex.value.value
  }
  implicit val documentFieldOrder: Order[DocumentField] = Order.by(_.value)
  implicit val actionOrder: Order[Action] = Order.by(_.value)
  implicit val authKeyOrder: Order[PlainTextSecret] = Order.by(_.value)
  implicit val indexOrder: Order[ClusterIndexName] = Order.by(_.stringify)
  implicit val userDefOrder: Order[UserDef] = Order.by(_.id.toString)
  implicit val ruleNameOrder: Order[Rule.Name] = Order.by(_.value)
  implicit val ruleOrder: Order[Rule] = Order.fromOrdering(new RuleOrdering)
  implicit val groupNameOrder: Order[GroupName] = Order.by(_.value)
  implicit val groupLikeOrder: Order[GroupLike] = Order.by(_.value)
  implicit val ruleWithVariableUsageDefinitionOrder: Order[RuleDefinition[Rule]] = Order.by(_.rule)
  implicit val patternOrder: Order[RegexPattern] = Order.by(_.pattern)
  implicit val forbiddenCauseOrder: Order[ForbiddenCause] = Order.by {
    case ForbiddenCause.OperationNotAllowed => 1
    case ForbiddenCause.ImpersonationNotAllowed => 2
    case ForbiddenCause.ImpersonationNotSupported => 3
  }
  implicit val repositoryOrder: Order[RepositoryName] =  Order.by {
    case RepositoryName.Full(value) => value.value
    case RepositoryName.Pattern(value) => value.value
    case RepositoryName.All => "_all"
    case RepositoryName.Wildcard => "*"
  }
  implicit val snapshotOrder: Order[SnapshotName] = Order.by {
    case SnapshotName.Full(value) => value.value
    case SnapshotName.Pattern(value) => value.value
    case SnapshotName.All => "_all"
    case SnapshotName.Wildcard => "*"
  }
  implicit val dataStreamOrder: Order[DataStreamName] = Order.by {
    case DataStreamName.Full(value) => value.value
    case DataStreamName.Pattern(value) => value.value
    case DataStreamName.All => "_all"
    case DataStreamName.Wildcard => "*"
  }

  implicit def accessOrder[T: Order]: Order[AccessRequirement[T]] = Order.from {
    case (MustBeAbsent(v1), MustBeAbsent(v2)) => v1.compare(v2)
    case (MustBePresent(v1), MustBePresent(v2)) => v1.compare(v2)
    case (MustBePresent(_), _) => -1
    case (_, MustBePresent(_)) => 1
  }
}

object show {
  trait LogsShowInstances {
    implicit val nonEmptyStringShow: Show[NonEmptyString] = Show.show(_.value)
    implicit val patternShow: Show[Pattern[_]] = Show.show(_.value.value)
    implicit val userIdShow: Show[User.Id] = Show.show(_.value.value)
    implicit val userIdPatternsShow: Show[UserIdPatterns] = Show.show(_.patterns.toList.map(_.value.value).mkString_(","))
    implicit val idPatternShow: Show[User.UserIdPattern] = patternShow.contramap(identity[Pattern[User.Id]])
    implicit val loggedUserShow: Show[LoggedUser] = Show.show(_.id.value.value)
    implicit val typeShow: Show[Type] = Show.show(_.value)
    implicit val actionShow: Show[Action] = Show.show(_.value)
    implicit val addressShow: Show[Address] = Show.show {
      case Address.Ip(value) => value.toString
      case Address.Name(value) => value.toString
    }
    implicit val ipShow: Show[Ip] = Show.show(_.value.toString())
    implicit val methodShow: Show[Method] = Show.show(_.m)
    implicit val jsonPathShow: Show[JsonPath] = Show.show(_.getPath)
    implicit val uriShow: Show[Uri] = Show.show(_.toJavaUri.toString())
    implicit val lemonUriShow: Show[LemonUri] = Show.show(_.toString())
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
    implicit val aliasPlaceholderShow: Show[AliasPlaceholder] = Show.show(_.alias.show)
    implicit val externalAuthenticationServiceNameShow: Show[ExternalAuthenticationService.Name] = Show.show(_.value.value)
    implicit val groupNameShow: Show[GroupName] = Show.show(_.value.value)
    implicit val tokenShow: Show[AuthorizationToken] = Show.show(_.value.value)
    implicit val jwtTokenShow: Show[Jwt.Token] = Show.show(_.value.value)
    implicit val uriPathShow: Show[UriPath] = Show.show(_.value.value)
    implicit val dnShow: Show[Dn] = Show.show(_.value.value)
    implicit val showGroupSearchFilter: Show[GroupSearchFilter] = Show.show(_.value.value)
    implicit val showGroupNameAttribute: Show[GroupNameAttribute] = Show.show(_.value.value)
    implicit val showUniqueMemberAttribute: Show[UniqueMemberAttribute] = Show.show(_.value.value)
    implicit val showGroupsFromUserAttribute: Show[GroupsFromUserAttribute] = Show.show(_.value.value)
    implicit val envNameShow: Show[EnvVarName] = Show.show(_.value.value)
    implicit val propNameShow: Show[PropName] = Show.show(_.value.value)
    implicit val templateNameShow: Show[TemplateName] = Show.show(_.value.value)
    implicit val templateNamePatternShow: Show[TemplateNamePattern] = Show.show(_.value.value)
    implicit val snapshotNameShow: Show[SnapshotName] = Show.show(v => SnapshotName.toString(v))
    implicit def ruleNameShow[T <: RuleName[_]]: Show[T] = Show.show(_.name.value)

    implicit def nonEmptyList[T : Show]: Show[NonEmptyList[T]] = Show[List[T]].contramap(_.toList)

    implicit def blockContextShow[B <: BlockContext](implicit showHeader: Show[Header]): Show[B] =
      Show.show { bc =>
        (showOption("user", bc.userMetadata.loggedUser) ::
          showOption("group", bc.userMetadata.currentGroup) ::
          showNamedIterable("av_groups", bc.userMetadata.availableGroups) ::
          showNamedIterable("indices", bc.indices) ::
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
        s"fields=[$commaSeparatedFields]"
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
        showOption("curr_group", u.currentGroup) ::
        showNamedIterable("av_groups", u.availableGroups) ::
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
        s"GETALL(${showIterable(op1.namePatterns.toList)}|${showIterable(op2.namePatterns.toList)})"
      case TemplateOperation.GettingLegacyTemplates(namePatterns) =>
        s"GET(${showIterable(namePatterns.toList)})"
      case TemplateOperation.AddingLegacyTemplate(name, patterns, aliases) =>
        s"ADD(${name.show}:${showIterable(patterns)}:${showIterable(aliases)})"
      case TemplateOperation.DeletingLegacyTemplates(namePatterns) =>
        s"DEL(${showIterable(namePatterns.toList)})"
      case TemplateOperation.GettingIndexTemplates(namePatterns) =>
        s"GET(${showIterable(namePatterns.toList)})"
      case TemplateOperation.AddingIndexTemplate(name, patterns, aliases) =>
        s"ADD(${name.show}:${showIterable(patterns.toList)}:${showIterable(aliases)})"
      case TemplateOperation.AddingIndexTemplateAndGetAllowedOnes(name, patterns, aliases, allowedTemplates) =>
        s"ADDGET(${name.show}:${showIterable(patterns.toList)}:${showIterable(aliases)}:${showIterable(allowedTemplates)})"
      case TemplateOperation.DeletingIndexTemplates(namePatterns) =>
        s"DEL(${showIterable(namePatterns.toList)})"
      case TemplateOperation.GettingComponentTemplates(namePatterns) =>
        s"GET(${showIterable(namePatterns.toList)})"
      case TemplateOperation.AddingComponentTemplate(name, aliases) =>
        s"ADD(${name.show}:${showIterable(aliases)})"
      case TemplateOperation.DeletingComponentTemplates(namePatterns) =>
        s"DEL(${showIterable(namePatterns.toList)})"
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
        s"""[${h.block.show}->$rulesHistoryItemsStr$resolvedPart]"""
      }

    implicit val policyShow: Show[Policy] = Show.show {
      case Allow => "ALLOW"
      case Forbid => "FORBID"
    }
    implicit val blockShow: Show[Block] = Show.show { b =>
      s"{ name: '${b.name.show}', policy: ${b.policy.show}, rules: [${b.rules.map(_.name.show).toList.mkString(",")}]"
    }
    implicit val runtimeResolvableVariableCreationErrorShow: Show[RuntimeResolvableVariableCreator.CreationError] = Show.show {
      case RuntimeResolvableVariableCreator.CreationError.CannotUserMultiVariableInSingleVariableContext =>
        "Cannot use multi value variable in non-array context"
      case RuntimeResolvableVariableCreator.CreationError.OnlyOneMultiVariableCanBeUsedInVariableDefinition =>
        "Cannot use more than one multi-value variable"
      case RuntimeResolvableVariableCreator.CreationError.InvalidVariableDefinition(cause) =>
        s"Variable malformed, cause: $cause"
      case RuntimeResolvableVariableCreator.CreationError.VariableConversionError(cause) =>
        cause
    }
    implicit val startupResolvableVariableCreationErrorShow: Show[StartupResolvableVariableCreator.CreationError] = Show.show {
      case StartupResolvableVariableCreator.CreationError.CannotUserMultiVariableInSingleVariableContext =>
        "Cannot use multi value variable in non-array context"
      case StartupResolvableVariableCreator.CreationError.OnlyOneMultiVariableCanBeUsedInVariableDefinition =>
        "Cannot use more than one multi-value variable"
      case StartupResolvableVariableCreator.CreationError.InvalidVariableDefinition(cause) =>
        s"Variable malformed, cause: $cause"
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

    def obfuscatedHeaderShow(obfuscatedHeaders: Set[Header.Name]): Show[Header] = {
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
        s"The '${block.show}' block should contain only one authentication rule, but contains: [${authRules.map(_.name.show).mkString_(",")}]"
      case BlockValidationError.KibanaRuleTogetherWithActionsRule =>
        s"The '${block.show}' block contains '${KibanaUserDataRule.Name.name.show}' rule (or deprecated '${KibanaAccessRule.Name.name.show}' rule) and '${ActionsRule.Name.name.show}' rule. These two cannot be used together in one block."
      case BlockValidationError.RuleDoesNotMeetRequirement(complianceResult) =>
        s"The '${block.show}' block doesn't meet requirements for defined variables. ${complianceResult.show}"
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
      else Some(s"$name=${showIterable(iterable)}")
    }

    private def showNamedNonEmptyList[T: Show](name: String, nonEmptyList: NonEmptyList[T]) = {
      showNamedIterable(name, nonEmptyList.toList)
    }

    private def showOption[T: Show](name: String, option: Option[T]) = {
      showNamedIterable(name, option.toList)
    }

    private def showIterable[T: Show](iterable: Iterable[T]) = {
      iterable.map(_.show).mkString(",")
    }

    implicit val authorizationValueErrorShow: Show[AuthorizationValueError] = Show.show {
      case AuthorizationValueError.EmptyAuthorizationValue => "Empty authorization value"
      case AuthorizationValueError.InvalidHeaderFormat(value) => s"Unexpected header format in ror_metadata: [$value]"
      case AuthorizationValueError.RorMetadataInvalidFormat(value, message) => s"Invalid format of ror_metadata: [$value], reason: [$message]"
    }

    implicit val unresolvableErrorShow: Show[Unresolvable] = Show.show {
      case Unresolvable.CannotExtractValue(msg) => s"Cannot extract variable value. $msg"
      case Unresolvable.CannotInstantiateResolvedValue(msg) => s"Extracted value type doesn't fit. $msg"
    }

    implicit def accessShow[T: Show]: Show[AccessRequirement[T]] = Show.show {
      case MustBePresent(value) => value.show
      case AccessRequirement.MustBeAbsent(value) => s"~${value.show}"
    }
  }
  object logs extends LogsShowInstances
}

object refined {
  implicit val finiteDurationValidate: Validate[FiniteDuration, Greater[Nat._0]] = Validate.fromPredicate(
    (d: FiniteDuration) => d.length > 0,
    (d: FiniteDuration) => s"$d is positive",
    Greater(shapeless.nat._0)
  )
}

object headerValues {
  implicit def nonEmptyListHeaderValue[T: ToHeaderValue]: ToHeaderValue[NonEmptyList[T]] = ToHeaderValue { list =>
    implicit val nesShow: Show[NonEmptyString] = Show.show(_.value)
    val tToHeaderValue = implicitly[ToHeaderValue[T]]
    NonEmptyString.unsafeFrom(list.map(tToHeaderValue.toRawValue).mkString_(","))
  }

  implicit val userIdHeaderValue: ToHeaderValue[User.Id] = ToHeaderValue(_.value)
  implicit val indexNameHeaderValue: ToHeaderValue[ClusterIndexName] = ToHeaderValue(_.nonEmptyStringify)

  implicit val transientFieldsToHeaderValue: ToHeaderValue[FieldsRestrictions] = ToHeaderValue { fieldsRestrictions =>
    import upickle.default
    import default._

    implicit val nesW: Writer[NonEmptyString] = StringWriter.comap(_.value)
    implicit val accessModeW: Writer[AccessMode] = Writer.merge(
      macroW[AccessMode.Whitelist.type],
      macroW[AccessMode.Blacklist.type]
    )
    implicit val documentFieldW: Writer[DocumentField] = macroW
    implicit val setW: Writer[UniqueNonEmptyList[DocumentField]] =
      SeqLikeWriter[UniqueNonEmptyList, DocumentField]

    implicit val fieldsRestrictionsW: Writer[FieldsRestrictions] = macroW

    val fieldsJsonString = upickle.default.write(fieldsRestrictions)
    NonEmptyString.unsafeFrom(
      Base64.getEncoder.encodeToString(fieldsJsonString.getBytes("UTF-8"))
    )
  }

  implicit val transientFieldsFromHeaderValue: FromHeaderValue[FieldsRestrictions] = (value: NonEmptyString) => {
    import upickle.default
    import default._

    implicit val nesR: Reader[NonEmptyString] = StringReader.map(NonEmptyString.unsafeFrom)
    implicit val accessModeR: Reader[AccessMode] = Reader.merge(
      macroR[AccessMode.Whitelist.type],
      macroR[AccessMode.Blacklist.type]
    )
    implicit val documentFieldR: Reader[DocumentField] = macroR

    implicit val setR: Reader[UniqueNonEmptyList[DocumentField]] =
      SeqLikeReader[List, DocumentField].map(UniqueNonEmptyList.unsafeFromIterable)

    implicit val fieldsRestrictionsR: Reader[FieldsRestrictions] = macroR

    Try(upickle.default.read[FieldsRestrictions](
      new String(Base64.getDecoder.decode(value.value), "UTF-8")
    ))
  }

  implicit val groupHeaderValue: ToHeaderValue[GroupName] = ToHeaderValue(_.value)
}