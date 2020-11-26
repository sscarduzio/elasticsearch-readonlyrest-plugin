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

import java.util.Base64
import java.util.regex.Pattern

import cats.data.NonEmptyList
import cats.implicits._
import cats.{Order, Show}
import com.softwaremill.sttp.{Method, Uri}
import eu.timepit.refined.api.Validate
import eu.timepit.refined.numeric.Greater
import eu.timepit.refined.types.string.NonEmptyString
import io.lemonlabs.uri.{Uri => LemonUri}
import shapeless.Nat
import tech.beshu.ror.accesscontrol.AccessControl.RegularRequestResult.ForbiddenByMismatched
import tech.beshu.ror.accesscontrol.blocks.Block.Policy.{Allow, Forbid}
import tech.beshu.ror.accesscontrol.blocks.Block.{History, HistoryItem, Name, Policy}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.Dn
import tech.beshu.ror.accesscontrol.blocks.definitions.{ExternalAuthenticationService, ProxyAuth, UserDef}
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RuleResult, RuleWithVariableUsageDefinition}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.VariableContext.UsageRequirement.{ComplianceResult, OneOfRuleBeforeMustBeAuthenticationRule}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.VariableContext.VariableType
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.{RuntimeResolvableVariableCreator, VariableContext}
import tech.beshu.ror.accesscontrol.blocks.variables.startup.StartupResolvableVariableCreator
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext, FilteredResponseFields, ResponseTransformation, RuleOrdering}
import tech.beshu.ror.accesscontrol.domain.AccessRequirement.{MustBeAbsent, MustBePresent}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions.{AccessMode, DocumentField}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.{FieldsRestrictions, Strategy}
import tech.beshu.ror.accesscontrol.domain.Header.AuthorizationValueError
import tech.beshu.ror.accesscontrol.domain.ResponseFieldsFiltering.{ResponseField, ResponseFieldsRestrictions}
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.factory.BlockValidator.BlockValidationError
import tech.beshu.ror.accesscontrol.header.{FromHeaderValue, ToHeaderValue}
import tech.beshu.ror.com.jayway.jsonpath.JsonPath
import tech.beshu.ror.providers.EnvVarProvider.EnvVarName
import tech.beshu.ror.providers.PropertiesProvider.PropName
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

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
  implicit val methodOrder: Order[Method] = Order.by(_.m)
  implicit val userIdOrder: Order[User.Id] = Order.by(_.value)
  implicit val apiKeyOrder: Order[ApiKey] = Order.by(_.value)
  implicit val kibanaAppOrder: Order[KibanaApp] = Order.by(_.value)
  implicit val documentFieldOrder: Order[DocumentField] = Order.by(_.value)
  implicit val actionOrder: Order[Action] = Order.by(_.value)
  implicit val authKeyOrder: Order[PlainTextSecret] = Order.by(_.value)
  implicit val indexOrder: Order[IndexName] = Order.by(_.value)
  implicit val userDefOrder: Order[UserDef] = Order.by(_.id.value)
  implicit val ruleNameOrder: Order[Rule.Name] = Order.by(_.value)
  implicit val ruleOrder: Order[Rule] = Order.fromOrdering(new RuleOrdering)
  implicit val ruleWithVariableUsageDefinitionOrder: Order[RuleWithVariableUsageDefinition[Rule]] = Order.by(_.rule)
  implicit val patternOrder: Order[Pattern] = Order.by(_.pattern)
  implicit val forbiddenByMismatchedCauseOrder: Order[ForbiddenByMismatched.Cause] = Order.by {
    case ForbiddenByMismatched.Cause.OperationNotAllowed => 1
    case ForbiddenByMismatched.Cause.ImpersonationNotAllowed => 2
    case ForbiddenByMismatched.Cause.ImpersonationNotSupported => 3
  }
  implicit val repositoryOrder: Order[RepositoryName] = Order.by(_.value.value)
  implicit val snapshotOrder: Order[SnapshotName] = Order.by(_.value.value)
  implicit def accessOrder[T : Order]: Order[AccessRequirement[T]] = Order.from {
    case (MustBeAbsent(v1), MustBeAbsent(v2)) => v1.compare(v2)
    case (MustBePresent(v1), MustBePresent(v2)) => v1.compare(v2)
    case (MustBePresent(_), _) => -1
    case (_, MustBePresent(_)) => 1
  }
}

object show {
  object logs {
    implicit val nonEmptyStringShow: Show[NonEmptyString] = Show.show(_.value)
    implicit val userIdShow: Show[User.Id] = Show.show(_.value.value)
    implicit val loggedUserShow: Show[LoggedUser] = Show.show(_.id.value.value)
    implicit val typeShow: Show[Type] = Show.show(_.value)
    implicit val actionShow: Show[Action] = Show.show(_.value)
    implicit val addressShow: Show[Address] = Show.show {
      case Address.Ip(value) => value.toString
      case Address.Name(value) => value.toString
    }
    implicit val methodShow: Show[Method] = Show.show(_.m)
    implicit val jsonPathShow: Show[JsonPath] = Show.show(_.getPath)
    implicit val uriShow: Show[Uri] = Show.show(_.toJavaUri.toString())
    implicit val lemonUriShow: Show[LemonUri] = Show.show(_.toString())
    implicit val headerNameShow: Show[Header.Name] = Show.show(_.value.value)
    implicit val kibanaAppShow: Show[KibanaApp] = Show.show(_.value.value)
    implicit val proxyAuthNameShow: Show[ProxyAuth.Name] = Show.show(_.value)
    implicit val indexNameShow: Show[IndexName] = Show.show(_.value.value)
    implicit val externalAuthenticationServiceNameShow: Show[ExternalAuthenticationService.Name] = Show.show(_.value)
    implicit val groupShow: Show[Group] = Show.show(_.value.value)
    implicit val tokenShow: Show[AuthorizationToken] = Show.show(_.value.value)
    implicit val jwtTokenShow: Show[JwtToken] = Show.show(_.value.value)
    implicit val uriPathShow: Show[UriPath] = Show.show(_.value)
    implicit val dnShow: Show[Dn] = Show.show(_.value.value)
    implicit val envNameShow: Show[EnvVarName] = Show.show(_.value.value)
    implicit val propNameShow: Show[PropName] = Show.show(_.value.value)
    implicit val repositoryShow: Show[RepositoryName] = Show.show(_.value.value)
    implicit val snapshotShow: Show[SnapshotName] = Show.show(_.value.value)
    implicit val templateNameShow: Show[TemplateName] = Show.show(_.value.value)

    implicit def blockContextShow[B <: BlockContext](implicit showHeader: Show[Header]): Show[B] =
      Show.show { bc =>
        (showOption("user", bc.userMetadata.loggedUser) ::
          showOption("group", bc.userMetadata.currentGroup) ::
          showTraversable("av_groups", bc.userMetadata.availableGroups) ::
          showTraversable("indices", bc.indices) ::
          showOption("kibana_idx", bc.userMetadata.kibanaIndex) ::
          showOption("fls", bc.fieldLevelSecurity) ::
          showTraversable("response_hdr", bc.responseHeaders) ::
          showTraversable("repositories", bc.repositories) ::
          showTraversable("snapshots", bc.snapshots) ::
          showTraversable("response_transformations", bc.responseTransformations) ::
          Nil flatten) mkString ";"
      }

    private implicit val responseTransformation: Show[ResponseTransformation] = Show.show {
      case FilteredResponseFields(ResponseFieldsRestrictions(fields, mode)) =>
        val commaSeparatedFields = fields.map(_.value.value).toList.mkString(",")
        s"FilteredResponseFields(fields=[$commaSeparatedFields],mode=$mode)"
    }

    private implicit val kibanaAccessShow: Show[KibanaAccess] = Show {
      case KibanaAccess.RO => "ro"
      case KibanaAccess.ROStrict => "ro_strict"
      case KibanaAccess.RW => "rw"
      case KibanaAccess.Admin => "admin"
      case KibanaAccess.Unrestricted => "unrestricted"
    }
    private implicit val userOriginShow: Show[UserOrigin] = Show.show(_.value.value)
    implicit val userMetadataShow: Show[UserMetadata] = Show.show { u =>
      (showOption("user", u.loggedUser) ::
        showOption("curr_group", u.currentGroup) ::
        showTraversable("av_groups", u.availableGroups) ::
        showOption("kibana_idx", u.kibanaIndex) ::
        showTraversable("hidden_apps", u.hiddenKibanaApps) ::
        showOption("kibana_access", u.kibanaAccess) ::
        showOption("user_origin", u.userOrigin) ::
        Nil flatten) mkString ";"
    }

    implicit val flsStrategyShow: Show[FieldLevelSecurity] = Show.show[FieldLevelSecurity] { fls =>
      fls.strategy match {
        case Strategy.FlsAtLuceneLevelApproach => "[strategy: fls_at_lucene_level]"
        case Strategy.BasedOnBlockContextOnly.EverythingAllowed => "[strategy: fls_at_es_level]"
        case Strategy.BasedOnBlockContextOnly.NotAllowedFieldsUsed(fields) => s"[strategy: fls_at_es_level, ${showNonEmptyList("not_allowed_fields_used", fields)}]"
      }
    }

    implicit val specificFieldShow: Show[FieldLevelSecurity.RequestFieldsUsage.UsedField.SpecificField] = Show.show(_.value)
    implicit val blockNameShow: Show[Name] = Show.show(_.value)

    implicit def historyItemShow[B <: BlockContext]: Show[HistoryItem[B]] = Show.show { hi =>
      s"${hi.rule.show}->${
        hi.result match {
          case RuleResult.Fulfilled(_) => "true"
          case RuleResult.Rejected(_) => "false"
        }
      }"
    }

    implicit def historyShow[B <: BlockContext](implicit headerShow: Show[Header]): Show[History[B]] =
      Show.show[History[B]] { h =>
        val resolvedPart = h.blockContext.show.some
          .filter(!_.isEmpty)
          .map(context => s", RESOLVED:[$context]").getOrElse("")
        s"""[${h.block.show}-> RULES:[${h.items.map(_.show).mkString(", ")}]$resolvedPart]"""
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
      case BlockValidationError.KibanaAccessRuleTogetherWithActionsRule =>
        s"The '${block.show}' block contains Kibana Access Rule and Actions Rule. These two cannot be used together in one block."
      case BlockValidationError.RuleDoesNotMeetRequirement(complianceResult) =>
        s"The '${block.show}' block doesn't meet requirements for defined variables. ${complianceResult.show}"
    }

    private def showTraversable[T: Show](name: String, traversable: Traversable[T]) = {
      if (traversable.isEmpty) None
      else Some(s"$name=${traversable.map(_.show).mkString(",")}")
    }

    private def showNonEmptyList[T: Show](name: String, nonEmptyList: NonEmptyList[T]) = {
      s"$name=${nonEmptyList.map(_.show).toList.mkString(",")}"
    }

    private def showOption[T: Show](name: String, option: Option[T]) = {
      option.map(v => s"$name=${v.show}")
    }

    implicit val authorizationValueErrorShow: Show[AuthorizationValueError] = Show.show {
      case AuthorizationValueError.EmptyAuthorizationValue => "Empty authorization value"
      case AuthorizationValueError.InvalidHeaderFormat(value) => s"Unexpected header format in ror_metadata: [$value]"
      case AuthorizationValueError.RorMetadataInvalidFormat(value, message) => s"Invalid format of ror_metadata: [$value], reason: [$message]"
    }

    implicit def accessShow[T : Show]: Show[AccessRequirement[T]] = Show.show {
      case MustBePresent(value) => value.show
      case AccessRequirement.MustBeAbsent(value) => s"~${value.show}"
    }
  }
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
  implicit val indexNameHeaderValue: ToHeaderValue[IndexName] = ToHeaderValue(_.value)

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
      SeqLikeReader[List, DocumentField]
        .map(UniqueNonEmptyList.unsafeFromList)

    implicit val fieldsRestrictionsR: Reader[FieldsRestrictions] = macroR

    Try(upickle.default.read[FieldsRestrictions](
      new String(Base64.getDecoder.decode(value.value), "UTF-8")
    ))
  }

  implicit val groupHeaderValue: ToHeaderValue[Group] = ToHeaderValue(_.value)
}