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
import cats.implicits.*
import cats.{Order, Show}
import eu.timepit.refined.api.*
import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.accesscontrol.AccessControlList.ForbiddenCause
import tech.beshu.ror.accesscontrol.blocks.*
import tech.beshu.ror.accesscontrol.blocks.Block.RuleDefinition
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.LdapConnectionConfig.*
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.*
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.VariableContext.UsageRequirement.*
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.domain.*
import tech.beshu.ror.accesscontrol.domain.AccessRequirement.{MustBeAbsent, MustBePresent}
import tech.beshu.ror.accesscontrol.domain.Address.Ip
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions.{AccessMode, DocumentField}
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.header.{FromHeaderValue, ToHeaderValue}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.utils.ScalaOps.*
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import java.util.Base64
import java.util.regex.Pattern as RegexPattern
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
    import default.*
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
    import default.*
    implicit val nesR: Reader[NonEmptyString] = StringReader.map(NonEmptyString.unsafeFrom)
    implicit val accessModeR: Reader[AccessMode] = Reader.merge(
      macroR[AccessMode.Whitelist.type],
      macroR[AccessMode.Blacklist.type]
    )
    implicit val documentFieldR: Reader[DocumentField] = macroR

    implicit val setR: Reader[UniqueNonEmptyList[DocumentField]] =
      SeqLikeReader[List, DocumentField].map(UniqueNonEmptyList.unsafeFrom)

    implicit val fieldsRestrictionsR: Reader[FieldsRestrictions] = macroR

    Try(upickle.default.read[FieldsRestrictions](
      new String(Base64.getDecoder.decode(value.value), "UTF-8")
    ))
  }

  implicit val groupHeaderValue: ToHeaderValue[GroupId] = ToHeaderValue(_.value)
}

object orders {
  implicit val nonEmptyStringOrder: Order[NonEmptyString] = Order.by(_.value)
  implicit val headerNameOrder: Order[Header.Name] = Order.by(_.value.value)
  implicit val headerOrder: Order[Header] = Order.by(h => (h.name, h.value.value))
  implicit val addressOrder: Order[Address] = Order.by {
    case Address.Ip(value) => value.toString()
    case Address.Name(value) => value.toString
  }
  implicit val methodOrder: Order[RequestContext.Method] = Order.by(_.value)
  implicit val apiKeyOrder: Order[ApiKey] = Order.by(_.value)
  implicit val kibanaAppOrder: Order[KibanaApp] = Order.by {
    case KibanaApp.FullNameKibanaApp(name) => name.value
    case KibanaApp.KibanaAppRegex(regex) => regex.value.value
  }
  implicit val documentFieldOrder: Order[DocumentField] = Order.by(_.value)
  implicit val actionOrder: Order[Action] = Order.by(_.value)
  implicit val authKeyOrder: Order[PlainTextSecret] = Order.by(_.value)
  implicit val custerIndexNameOrder: Order[ClusterIndexName] = Order.by(_.stringify)
  implicit val requestedIndexOrder: Order[RequestedIndex[ClusterIndexName]] = Order.by(r => (r.excluded, r.name))
  implicit val userDefOrder: Order[UserDef] = Order.by(_.id.toString)
  implicit val ruleNameOrder: Order[Rule.Name] = Order.by(_.value)
  implicit val ruleOrder: Order[Rule] = Order.fromOrdering(new RuleOrdering)
  implicit val groupIdOrder: Order[GroupId] = Order.by(_.value)
  implicit val groupIdLikeOrder: Order[GroupIdLike] = Order.by {
    case GroupId(value) => value
    case GroupIdLike.GroupIdPattern(value) => value
  }
  implicit val ruleWithVariableUsageDefinitionOrder: Order[RuleDefinition[Rule]] = Order.by(_.rule)
  implicit val patternOrder: Order[RegexPattern] = Order.by(_.pattern)
  implicit val forbiddenCauseOrder: Order[ForbiddenCause] = Order.by {
    case ForbiddenCause.OperationNotAllowed => 1
    case ForbiddenCause.ImpersonationNotAllowed => 2
    case ForbiddenCause.ImpersonationNotSupported => 3
  }
  implicit val repositoryOrder: Order[RepositoryName] = Order.by {
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

  def userIdOrder(globalSettings: GlobalSettings): Order[User.Id] = Order.by { userId =>
    globalSettings.userIdCaseSensitivity match {
      case CaseSensitivity.Enabled => userId.value.value
      case CaseSensitivity.Disabled => userId.value.value.toLowerCase
    }
  }
}
