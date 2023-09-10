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
package tech.beshu.ror.accesscontrol.domain

import cats.Eq
import cats.data.NonEmptyList
import cats.implicits._
import cats.kernel.Monoid
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto.autoUnwrap
import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Remote.ClusterName
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions.DocumentField
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.RequestFieldsUsage.UsedField.SpecificField
import tech.beshu.ror.utils.CaseMappingEquality
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import scala.util.Random

sealed trait Action {
  def value: String
}
object Action {
  final case class EsAction(override val value: String) extends Action
  private object EsAction {
    val fieldCapsAction: Action = EsAction("indices:data/read/field_caps")
    val getSettingsAction: Action = EsAction("indices:monitor/settings/get")
    val monitorStateAction: Action = EsAction("cluster:monitor/state")
  }

  abstract sealed class RorAction(override val value: String) extends Action
  object RorAction {
    case object RorUserMetadataAction extends RorAction("cluster:internal_ror/user_metadata/get")
    case object RorConfigAction extends RorAction("cluster:internal_ror/config/manage")
    case object RorTestConfigAction extends RorAction("cluster:internal_ror/testconfig/manage")
    case object RorAuthMockAction extends RorAction("cluster:internal_ror/authmock/manage")
    case object RorAuditEventAction extends RorAction("cluster:internal_ror/audit_event/put")
    case object RorOldConfigAction extends RorAction("cluster:internal_ror/config/refreshsettings")

    def fromString(value: String): Option[Action] = {
      rorActionFrom(value)
        .orElse(rorActionByOutdatedName.get(value))
        .orElse(patternMatchingOutdatedRorActionName(value))
    }

    private def rorActionFrom(value: String): Option[RorAction] = value match {
      case RorUserMetadataAction.`value` => RorUserMetadataAction.some
      case RorConfigAction.`value` => RorConfigAction.some
      case RorTestConfigAction.`value` => RorTestConfigAction.some
      case RorAuthMockAction.`value` => RorAuthMockAction.some
      case RorAuditEventAction.`value` => RorAuditEventAction.some
      case RorOldConfigAction.`value` => RorOldConfigAction.some
      case _ => None
    }

    private val rorActionByOutdatedName: Map[String, RorAction] = Map(
      "cluster:ror/user_metadata/get" -> RorUserMetadataAction,
      "cluster:ror/config/manage" -> RorConfigAction,
      "cluster:ror/testconfig/manage" -> RorTestConfigAction,
      "cluster:ror/authmock/manage" -> RorAuthMockAction,
      "cluster:ror/audit_event/put" -> RorAuditEventAction,
      "cluster:ror/config/refreshsettings" -> RorOldConfigAction
    )

    private def patternMatchingOutdatedRorActionName(possiblePattern: String): Option[EsAction] = {
      Option(possiblePattern)
        .filter(_.contains("*"))
        .filter(_.startsWith("cluster:r"))
        .filter { pattern =>
          val prefix = pattern.takeWhile(_ == '*')
          rorActionByOutdatedName.keys.exists(_.startsWith(prefix)) // does any of the old names match pattern
        }
        .map { value =>
          EsAction(value.stripPrefix("cluster:").prependedAll("cluster:internal_"))
        }
    }
  }

  def apply(value: String): Action = {
    RorAction.fromString(value)
      .getOrElse(EsAction(value))
  }

  def isInternal(actionString: String): Boolean = actionString.startsWith("internal:")
  def isMonitorState(actionString: String): Boolean = actionString == EsAction.monitorStateAction.value
  def isXpackSecurity(actionString: String): Boolean = actionString.startsWith("cluster:admin/xpack/security/")
  def isRollupAction(actionString: String): Boolean = actionString.startsWith("cluster:admin/xpack/rollup/")

  implicit class ActionOps(val action: Action) extends AnyVal {
    def isRorAction: Boolean = action match {
      case _: EsAction => false
      case _: RorAction => true
    }

    def isXpackSecurityAction: Boolean =
      Action.isXpackSecurity(action.value)

    def isFieldCapsAction: Boolean =
      action == EsAction.fieldCapsAction

    def isRollupAction: Boolean =
      Action.isRollupAction(action.value)

    def isGetSettingsAction: Boolean =
      action == EsAction.getSettingsAction

    def isInternal: Boolean =
      Action.isInternal(action.value)
  }

  implicit val eqAction: Eq[Action] = Eq.fromUniversalEquals
  implicit val caseMappingEqualityAction: CaseMappingEquality[Action] = CaseMappingEquality.instance(_.value, identity)
}

final case class DocumentId(value: String) extends AnyVal

final case class DocumentWithIndex(index: ClusterIndexName, documentId: DocumentId)

sealed trait RepositoryName
object RepositoryName {
  final case class Full private(value: NonEmptyString) extends RepositoryName
  final case class Pattern private(value: NonEmptyString) extends RepositoryName
  case object All extends RepositoryName
  case object Wildcard extends RepositoryName

  def all: RepositoryName = All

  def wildcard: RepositoryName = Wildcard

  def from(value: String): Option[RepositoryName] = {
    NonEmptyString.unapply(value).map {
      case Refined("_all") => All
      case Refined("*") => Wildcard
      case v if v.contains("*") => Pattern(NonEmptyString.unsafeFrom(v))
      case v => Full(NonEmptyString.unsafeFrom(v))
    }
  }

  def toString(snapshotName: RepositoryName): String = snapshotName match {
    case Full(value) => value.value
    case Pattern(value) => value.value
    case All => "_all"
    case Wildcard => "*"
  }

  implicit val eqRepository: Eq[RepositoryName] = Eq.fromUniversalEquals
  implicit val caseMappingEqualityRepositoryName: CaseMappingEquality[RepositoryName] = CaseMappingEquality.instance(
    {
      case Full(value) => value.value
      case Pattern(value) => value.value
      case All => "*"
      case Wildcard => "*"
    },
    identity
  )
}

sealed trait SnapshotName
object SnapshotName {
  final case class Full private(value: NonEmptyString) extends SnapshotName
  final case class Pattern private(value: NonEmptyString) extends SnapshotName
  case object All extends SnapshotName
  case object Wildcard extends SnapshotName

  def all: SnapshotName = SnapshotName.All

  def wildcard: SnapshotName = SnapshotName.Wildcard

  def from(value: String): Option[SnapshotName] = {
    NonEmptyString.unapply(value).map {
      case Refined("_all") => All
      case Refined("*") => Wildcard
      case v if v.contains("*") => Pattern(NonEmptyString.unsafeFrom(v))
      case v => Full(NonEmptyString.unsafeFrom(v))
    }
  }

  def toString(snapshotName: SnapshotName): String = snapshotName match {
    case Full(value) => value.value
    case Pattern(value) => value.value
    case All => "_all"
    case Wildcard => "*"
  }

  implicit val eqSnapshotName: Eq[SnapshotName] = Eq.fromUniversalEquals
  implicit val caseMappingEqualitySnapshotName: CaseMappingEquality[SnapshotName] = CaseMappingEquality.instance(
    {
      case Full(value) => value.value
      case Pattern(value) => value.value
      case All => "*"
      case Wildcard => "*"
    },
    identity
  )
}

sealed trait DataStreamName
object DataStreamName {
  final case class Full private(value: NonEmptyString) extends DataStreamName
  object Full {
    def fromString(value: String): Option[DataStreamName.Full] = {
      NonEmptyString.unapply(value).map(DataStreamName.Full.apply)
    }
  }

  final case class Pattern private(value: NonEmptyString) extends DataStreamName
  case object All extends DataStreamName
  case object Wildcard extends DataStreamName

  def all: DataStreamName = DataStreamName.All

  def wildcard: DataStreamName = DataStreamName.Wildcard

  def fromString(value: String): Option[DataStreamName] = {
    NonEmptyString.unapply(value).map {
      case Refined("_all") => All
      case Refined("*") => Wildcard
      case v if v.contains("*") => Pattern(NonEmptyString.unsafeFrom(v))
      case v => Full(NonEmptyString.unsafeFrom(v))
    }
  }

  def toString(dataStreamName: DataStreamName): String = dataStreamName match {
    case Full(value) => value.value
    case Pattern(value) => value.value
    case All => "_all"
    case Wildcard => "*"
  }

  implicit val eqDataStreamName: Eq[DataStreamName] = Eq.fromUniversalEquals
  implicit val caseMappingEqualityDataStreamName: CaseMappingEquality[DataStreamName] = CaseMappingEquality.instance(
    {
      case Full(value) => value.value
      case Pattern(value) => value.value
      case All => "*"
      case Wildcard => "*"
    },
    identity
  )

  final case class FullLocalDataStreamWithAliases(dataStreamName: DataStreamName.Full,
                                                  aliasesNames: Set[DataStreamName.Full],
                                                  backingIndices: Set[IndexName.Full]) {
    lazy val attribute: IndexAttribute = IndexAttribute.Opened // data streams cannot be closed
    lazy val dataStream: ClusterIndexName.Local = toLocalIndex(dataStreamName)
    lazy val aliases: Set[ClusterIndexName.Local] = aliasesNames.map(toLocalIndex)
    lazy val indices: Set[ClusterIndexName.Local] = backingIndices.map(ClusterIndexName.Local.apply)
    lazy val all: Set[ClusterIndexName.Local] = aliases ++ indices + dataStream

    private def toLocalIndex(ds: DataStreamName.Full): ClusterIndexName.Local =
      ClusterIndexName.Local(IndexName.Full(ds.value))
  }

  final case class FullRemoteDataStreamWithAliases(clusterName: ClusterName.Full,
                                                   dataStreamName: DataStreamName.Full,
                                                   aliasesNames: Set[DataStreamName.Full],
                                                   backingIndices: Set[IndexName.Full]) {
    lazy val attribute: IndexAttribute = IndexAttribute.Opened // data streams cannot be closed
    lazy val dataStream: ClusterIndexName.Remote = toRemoteIndex(dataStreamName)
    lazy val aliases: Set[ClusterIndexName.Remote] = aliasesNames.map(toRemoteIndex)
    lazy val indices: Set[ClusterIndexName.Remote] = backingIndices.map(ClusterIndexName.Remote(_, clusterName))
    lazy val all: Set[ClusterIndexName.Remote] = aliases ++ indices + dataStream

    private def toRemoteIndex(ds: DataStreamName.Full): ClusterIndexName.Remote =
      ClusterIndexName.Remote(IndexName.Full(ds.value), clusterName)
  }
}

object ResponseFieldsFiltering {

  final case class ResponseFieldsRestrictions(responseFields: UniqueNonEmptyList[ResponseField],
                                              mode: AccessMode)

  final case class ResponseField(value: NonEmptyString)

  sealed trait AccessMode
  object AccessMode {
    case object Whitelist extends AccessMode
    case object Blacklist extends AccessMode
  }
}

sealed trait DocumentAccessibility
object DocumentAccessibility {
  case object Accessible extends DocumentAccessibility
  case object Inaccessible extends DocumentAccessibility
}

final case class FieldLevelSecurity(restrictions: FieldLevelSecurity.FieldsRestrictions,
                                    strategy: FieldLevelSecurity.Strategy)

object FieldLevelSecurity {

  final case class FieldsRestrictions(documentFields: UniqueNonEmptyList[DocumentField],
                                      mode: FieldsRestrictions.AccessMode)

  object FieldsRestrictions {
    final case class DocumentField(value: NonEmptyString)

    sealed trait AccessMode
    object AccessMode {
      case object Whitelist extends AccessMode
      case object Blacklist extends AccessMode
    }
  }

  sealed trait Strategy
  object Strategy {
    case object FlsAtLuceneLevelApproach extends Strategy
    sealed trait BasedOnBlockContextOnly extends Strategy

    object BasedOnBlockContextOnly {
      case object EverythingAllowed extends BasedOnBlockContextOnly
      final case class NotAllowedFieldsUsed(fields: NonEmptyList[SpecificField]) extends BasedOnBlockContextOnly
    }
  }

  sealed trait RequestFieldsUsage
  object RequestFieldsUsage {

    case object CannotExtractFields extends RequestFieldsUsage
    case object NotUsingFields extends RequestFieldsUsage
    final case class UsingFields(usedFields: NonEmptyList[UsedField]) extends RequestFieldsUsage

    sealed trait UsedField {
      def value: String
    }

    object UsedField {

      final case class SpecificField private(value: String) extends UsedField

      object SpecificField {
        implicit class Ops(val specificField: SpecificField) extends AnyVal {
          def obfuscate: ObfuscatedRandomField = ObfuscatedRandomField(specificField)
        }
      }

      final case class FieldWithWildcard private(value: String) extends UsedField

      def apply(value: String): UsedField = {
        if (hasWildcard(value))
          FieldWithWildcard(value)
        else
          SpecificField(value)
      }

      private def hasWildcard(fieldName: String): Boolean = fieldName.contains("*")
    }

    final case class ObfuscatedRandomField(value: String) extends AnyVal
    object ObfuscatedRandomField {
      def apply(from: SpecificField): ObfuscatedRandomField = {
        new ObfuscatedRandomField(s"${from.value}_ROR_${Random.alphanumeric.take(10).mkString("")}")
      }
    }

    implicit val monoidInstance: Monoid[RequestFieldsUsage] = Monoid.instance(NotUsingFields, {
      case (CannotExtractFields, _) => CannotExtractFields
      case (_, CannotExtractFields) => CannotExtractFields
      case (other, NotUsingFields) => other
      case (NotUsingFields, other) => other
      case (UsingFields(firstFields), UsingFields(secondFields)) => UsingFields(firstFields ::: secondFields)
    })
  }

}

final case class Filter(value: NonEmptyString)

final case class Type(value: String) extends AnyVal