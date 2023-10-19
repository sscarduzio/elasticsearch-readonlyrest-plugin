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
import eu.timepit.refined.auto._
import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.constants
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Remote.ClusterName
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher.Matchable
import tech.beshu.ror.utils.ScalaOps._

import java.time.{Instant, ZoneId}
import java.time.format.DateTimeFormatter
import scala.language.postfixOps
import scala.util.{Failure, Random, Success, Try}

sealed trait IndexName
object IndexName {

  val wildcard: IndexName.Pattern = IndexName.Pattern("*")

  final case class Full(name: NonEmptyString)
    extends IndexName
  object Full {
    def fromString(value: String): Option[Full] =
      NonEmptyString.unapply(value).map(Full.apply)
  }

  final case class Pattern private(name: NonEmptyString)
    extends IndexName
  object Pattern {
    def fromString(value: String): Option[Pattern] =
      NonEmptyString
        .unapply(value)
        .flatMap {
          case str if str.value == "_all" => Some(IndexName.wildcard)
          case str if str.contains("*") => Some(IndexName.Pattern(NonEmptyString.unsafeFrom(str)))
          case _ => None
        }
  }

  def fromString(value: String): Option[IndexName] =
    IndexName.Pattern.fromString(value) orElse IndexName.Full.fromString(value)

  implicit val matchableIndexName: Matchable[IndexName] = Matchable.matchable{
    case IndexName.Full(name) => name
    case IndexName.Pattern(namePattern) => namePattern
  }
}

final case class KibanaIndexName(underlying: ClusterIndexName.Local)
object KibanaIndexName {

  private val kibanaRelatedIndicesSuffixRegexes = Vector(
    """^_\d+\.\d+\.\d+$""".r, // eg. .kibana_8.0.0
    """^_alerting_cases$""".r, // eg. .kibana_alerting_cases
    """^_alerting_cases_\d+\.\d+\.\d+$""".r, // eg. .kibana_alerting_cases_8.8.0
    """^_analytics$""".r, // eg. .kibana_analytics
    """^_analytics_\d+\.\d+\.\d+$""".r, // eg. .kibana_analytics_8.0.0
    """^_ingest$""".r, // eg. .kibana_ingest
    """^_ingest_\d+\.\d+\.\d+$""".r, // eg. .kibana_ingest_8.0.0
    """^-event-log-\d+\.\d+\.\d+$""".r, // eg. .kibana-event-log-8.8.0
    """^_security_solution$""".r, // eg. .kibana_security_solution
    """^_security_solution_\d+\.\d+\.\d+$""".r, // eg. .kibana_security_solution_8.8.0
    """^_task_manager$""".r, // eg. .kibana_task_manager
    """^_task_manager_\d+\.\d+\.\d+$""".r, // eg. .kibana_task_manager_8.8.0
  )

  implicit class IsRelatedToKibanaIndex(val indexName: ClusterIndexName) extends AnyVal {
    def isRelatedToKibanaIndex(kibanaIndex: KibanaIndexName): Boolean = {
      if (indexName == kibanaIndex.underlying) {
        true
      } else if (isPrefixedBy(kibanaIndex)) {
        val indexNameStringWithoutKibanaIndexNamePrefix = getNameWithoutKibanaIndexNamePrefix(kibanaIndex)
        kibanaRelatedIndicesSuffixRegexes.exists(_.matches(indexNameStringWithoutKibanaIndexNamePrefix))
      } else {
        false
      }
    }

    private def isPrefixedBy(kibanaIndex: KibanaIndexName) = {
      indexName.stringify.startsWith(kibanaIndex.underlying.stringify)
    }

    private def getNameWithoutKibanaIndexNamePrefix(kibanaIndex: KibanaIndexName) = {
      indexName.stringify.replace(kibanaIndex.underlying.stringify, "")
    }
  }

  implicit class Stringify(val kibanaIndexName: KibanaIndexName) extends AnyVal {
    def stringify: String = kibanaIndexName.underlying.stringify
  }
}

sealed trait ClusterIndexName {
  private [domain] lazy val matcher = PatternsMatcher.create(this :: Nil)
}
object ClusterIndexName {

  final case class Local private(value: IndexName) extends ClusterIndexName
  object Local {

    val wildcard: ClusterIndexName.Local = Local(IndexName.wildcard)
    val devNullKibana: KibanaIndexName = KibanaIndexName(Local(IndexName.Full(".kibana-devnull")))
    val kibanaDefault: KibanaIndexName = KibanaIndexName(Local(IndexName.Full(".kibana")))

    def fromString(value: String): Option[ClusterIndexName.Local] = {
      IndexName
        .fromString(value)
        .map(Local.apply)
    }

    def randomNonexistentIndex(prefix: String = ""): ClusterIndexName.Local = fromString {
      val nonexistentIndex = s"${NonEmptyString.unapply(prefix).map(i => s"${i}_").getOrElse("")}ROR_${Random.alphanumeric.take(10).mkString("")}"
      if (prefix.contains("*")) s"$nonexistentIndex*"
      else nonexistentIndex
    } get

    implicit val matchableClusterIndexNameLocal: Matchable[ClusterIndexName.Local] = Matchable.matchable(_.stringify)
  }

  final case class Remote(value: IndexName, cluster: ClusterName) extends ClusterIndexName
  object Remote {
    sealed trait ClusterName
    object ClusterName {
      final case class Full private(value: NonEmptyString) extends ClusterName
      object Full {
        def fromString(value: String): Option[Full] =
          NonEmptyString.unapply(value).map(Full.apply)

      }

      final case class Pattern private(value: NonEmptyString) extends ClusterName
      object Pattern {
        def fromString(value: String): Option[Pattern] =
          NonEmptyString.unapply(value).flatMap {
            case str if str.contains("*") => Some(ClusterName.Pattern(NonEmptyString.unsafeFrom(str)))
            case _ => None
          }
      }

      def fromString(value: String): Option[ClusterName] = {
        Pattern.fromString(value) orElse Full.fromString(value)
      }

      val wildcard: ClusterName.Pattern = Pattern("*")

      implicit val matchableClusterName: Matchable[ClusterName] = Matchable.matchable(_.stringify)

      implicit class Stringify(val clusterName: ClusterName) extends AnyVal {
        def stringify: String = clusterName match {
          case Full(name) => name
          case Pattern(namePattern) => namePattern
        }
      }
    }

    def fromString(value: String): Option[ClusterIndexName.Remote] = {
      value.splitBy(":") match {
        case (_, None) =>
          None
        case (clusterPart, Some(indexNamePart)) =>
          for {
            cluster <- ClusterName.fromString(clusterPart)
            indexName <- IndexName.fromString(indexNamePart)
          } yield Remote(indexName, cluster)
      }
    }

    implicit val matchableClusterIndexNameRemote: Matchable[ClusterIndexName.Remote] = Matchable.matchable(_.stringify)
  }

  def fromString(value: String): Option[ClusterIndexName] = {
    Remote.fromString(value) orElse Local.fromString(value)
  }

  def unsafeFromString(value: String): ClusterIndexName =
    fromString(value).getOrElse(throw new IllegalStateException(s"Cannot create an index name from '$value'"))

  implicit val matchableClusterIndexName: Matchable[ClusterIndexName] = Matchable.matchable(_.stringify)

  implicit val eqIndexName: Eq[ClusterIndexName] = Eq.fromUniversalEquals

  implicit class IndexMatch(indexName: ClusterIndexName) {

    def matches(otherIndexName: ClusterIndexName): Boolean = indexName match {
      case Local(IndexName.Full(_)) => indexName == otherIndexName
      case Remote(IndexName.Full(_), _) => indexName == otherIndexName
      case Local(IndexName.Pattern(_)) => indexName.matcher.`match`(otherIndexName)
      case Remote(IndexName.Pattern(_), _) => indexName.matcher.`match`(otherIndexName)
    }
  }

  implicit class RandomNonexistentIndex(val base: ClusterIndexName) extends AnyVal {
    def randomNonexistentIndex(): ClusterIndexName = base match {
      case Local(IndexName.Full(name)) =>
        Local.randomNonexistentIndex(name)
      case Local(IndexName.Pattern(namePattern)) =>
        Local.randomNonexistentIndex(namePattern)
      case Remote(IndexName.Full(name), clusterName) =>
        Local.randomNonexistentIndex(s"${clusterName.stringify}_$name")
      case Remote(IndexName.Pattern(namePattern), clusterName) =>
        Local.randomNonexistentIndex(s"${clusterName.stringify}_$namePattern")
    }
  }

  implicit class Stringify(val indexName: ClusterIndexName) extends AnyVal {
    def stringify: String = indexName match {
      case Local(IndexName.Full(name)) => name
      case Local(IndexName.Pattern(namePattern)) => namePattern
      case Remote(IndexName.Full(name), cluster) => s"${cluster.stringify}:$name"
      case Remote(IndexName.Pattern(namePattern), cluster) => s"${cluster.stringify}:$namePattern"
    }

    def nonEmptyStringify: NonEmptyString = indexName match {
      case Local(IndexName.Full(name)) => name
      case Local(IndexName.Pattern(namePattern)) => namePattern
      case Remote(IndexName.Full(name), cluster) => NonEmptyString.unsafeFrom(s"${cluster.stringify}:$name")
      case Remote(IndexName.Pattern(namePattern), cluster) => NonEmptyString.unsafeFrom(s"${cluster.stringify}:$namePattern")
    }
  }

  implicit class OnlyIndexName(val remoteIndexName: ClusterIndexName.Remote) extends AnyVal {
    def onlyIndexName: NonEmptyString = remoteIndexName match {
      case Remote(IndexName.Full(name), _) => name
      case Remote(IndexName.Pattern(namePattern), _) => namePattern
    }
  }

  implicit class HasPrefix(val indexName: ClusterIndexName) extends AnyVal {
    def hasPrefix(prefix: String): Boolean = {
      indexName match {
        case local: Local => local.stringify.startsWith(prefix)
        case remote: Remote => remote.onlyIndexName.startsWith(prefix)
      }
    }
  }

  implicit class IsAllowedBy(val indexName: ClusterIndexName) extends AnyVal {
    def isAllowedBy(allowedIndices: Iterable[ClusterIndexName]): Boolean = {
      indexName match {
        case Placeholder(placeholder) =>
          val potentialAliases = allowedIndices.map(i => placeholder.index(i.nonEmptyStringify))
          potentialAliases.exists { alias => allowedIndices.exists(_.matches(alias)) }
        case _ =>
          allowedIndices.exists(_.matches(indexName))
      }
    }
  }

  implicit class HasWildcard(val indexName: ClusterIndexName) extends AnyVal {
    def hasWildcard: Boolean = indexName match {
      case Local(IndexName.Full(_)) => false
      case Local(IndexName.Pattern(_)) => true
      case Remote(IndexName.Full(_), _) => false
      case Remote(IndexName.Pattern(_), _) => true
    }
  }

  implicit class AllIndicesRequested(val indexName: ClusterIndexName) extends AnyVal {
    def allIndicesRequested: Boolean = indexName match {
      case Local(IndexName.wildcard) => true
      case Local(IndexName.Full(_) | IndexName.Pattern(_)) => false
      case Remote(IndexName.wildcard, ClusterName.wildcard) => true
      case Remote(IndexName.Full(_) | IndexName.Pattern(_), _) => false
    }
  }

  implicit class ToDataStreamBackingIndexNameFormat(val index: ClusterIndexName) extends AnyVal {

    def formatAsDataStreamBackingIndexName: ClusterIndexName = {
      format(backingIndexWildcardNameFrom)
    }

    def formatAsLegacyDataStreamBackingIndexName: ClusterIndexName = {
      format(legacyBackingIndexWildcardNameFrom)
    }

    private def format(backingIndexWildcardFromString: NonEmptyString => IndexName.Pattern) = {
      index match {
        case ClusterIndexName.Local(IndexName.Full(name)) if !doesItLookLikeABackingIndex(name) =>
          ClusterIndexName.Local(backingIndexWildcardFromString(name))
        case ClusterIndexName.Local(IndexName.Pattern(namePattern)) if !doesItLookLikeABackingIndex(namePattern) =>
          ClusterIndexName.Local(backingIndexWildcardFromString(namePattern))
        case ClusterIndexName.Remote(IndexName.Full(name), cluster) if !doesItLookLikeABackingIndex(name) =>
          ClusterIndexName.Remote(backingIndexWildcardFromString(name), cluster)
        case ClusterIndexName.Remote(IndexName.Pattern(namePattern), cluster) if !doesItLookLikeABackingIndex(namePattern) =>
          ClusterIndexName.Remote(backingIndexWildcardFromString(namePattern), cluster)
        case index =>
          index
      }
    }

    private def doesItLookLikeABackingIndex(nameStr: NonEmptyString) = {
      nameStr.value.startsWith(".ds-")
    }

    private def backingIndexWildcardNameFrom(nameStr: NonEmptyString) = {
      IndexName.Pattern(NonEmptyString.unsafeFrom(s".ds-$nameStr-*.*.*-*"))
    }

    private def legacyBackingIndexWildcardNameFrom(nameStr: NonEmptyString) = {
      IndexName.Pattern(NonEmptyString.unsafeFrom(s".ds-$nameStr-*"))
    }
  }

}

final case class IndexPattern(value: ClusterIndexName) {
  private lazy val matcher = PatternsMatcher.create(value :: Nil)

  def isAllowedBy(index: ClusterIndexName): Boolean = {
    matcher.`match`(index) || index.matches(value)
  }

  def isAllowedByAny(anyIndexFrom: Iterable[ClusterIndexName]): Boolean = {
    anyIndexFrom.exists(this.isAllowedBy)
  }

  def isSubsetOf(index: ClusterIndexName): Boolean = {
    index.matches(value)
  }
}
object IndexPattern {

  def fromString(value: String): Option[IndexPattern] =
    ClusterIndexName.fromString(value).map(IndexPattern.apply)
}

final case class AliasPlaceholder private(alias: ClusterIndexName) extends AnyVal {
  def index(value: NonEmptyString): ClusterIndexName = alias match {
    case ClusterIndexName.Local(IndexName.Full(_)) =>
      val replaced = alias.stringify.replaceAll(AliasPlaceholder.escapedPlaceholder, value.value)
      ClusterIndexName.Local(IndexName.Full(NonEmptyString.unsafeFrom(replaced)))
    case ClusterIndexName.Local(IndexName.Pattern(_)) =>
      val replaced = alias.stringify.replaceAll(AliasPlaceholder.escapedPlaceholder, value.value)
      ClusterIndexName.Local(IndexName.Pattern(NonEmptyString.unsafeFrom(replaced)))
    case i@ClusterIndexName.Remote(IndexName.Full(_), clusterName) =>
      val replaced = i.onlyIndexName.replaceAll(AliasPlaceholder.escapedPlaceholder, value.value)
      ClusterIndexName.Remote(IndexName.Full(NonEmptyString.unsafeFrom(replaced)), clusterName)
    case i@ClusterIndexName.Remote(IndexName.Pattern(_), clusterName) =>
      val replaced = i.onlyIndexName.replaceAll(AliasPlaceholder.escapedPlaceholder, value.value)
      ClusterIndexName.Remote(IndexName.Pattern(NonEmptyString.unsafeFrom(replaced)), clusterName)
  }
}
object AliasPlaceholder {
  private val placeholder = "{index}"
  private val escapedPlaceholder = placeholder.replace("{", "\\{").replace("}", "\\}")

  def from(alias: ClusterIndexName): Option[AliasPlaceholder] = alias match {
    case ClusterIndexName.Local(IndexName.Full(name)) if name.contains(placeholder) =>
      Some(AliasPlaceholder(alias))
    case ClusterIndexName.Local(IndexName.Full(_)) =>
      None
    case ClusterIndexName.Local(IndexName.Pattern(namePattern)) if namePattern.contains(placeholder) =>
      Some(AliasPlaceholder(alias))
    case ClusterIndexName.Local(IndexName.Pattern(_)) =>
      None
    case ClusterIndexName.Remote(IndexName.Full(name), _) if name.contains(placeholder) =>
      Some(AliasPlaceholder(alias))
    case ClusterIndexName.Remote(IndexName.Full(_), _) =>
      None
    case ClusterIndexName.Remote(IndexName.Pattern(namePattern), _) if namePattern.contains(placeholder) =>
      Some(AliasPlaceholder(alias))
    case ClusterIndexName.Remote(IndexName.Pattern(_), _) =>
      None
  }
}

object Placeholder {
  def unapply(alias: ClusterIndexName): Option[AliasPlaceholder] = AliasPlaceholder.from(alias)
}

final case class FullLocalIndexWithAliases(indexName: IndexName.Full,
                                           attribute: IndexAttribute,
                                           aliasesNames: Set[IndexName.Full]) {
  lazy val index: ClusterIndexName.Local = ClusterIndexName.Local(indexName)
  lazy val aliases: Set[ClusterIndexName.Local] = aliasesNames.map(ClusterIndexName.Local.apply)
  lazy val all: Set[ClusterIndexName.Local] = aliases + index
}

final case class FullRemoteIndexWithAliases(clusterName: ClusterName.Full,
                                            indexName: IndexName.Full,
                                            attribute: IndexAttribute,
                                            aliasesNames: Set[IndexName.Full]) {
  lazy val index: ClusterIndexName.Remote = ClusterIndexName.Remote(indexName, clusterName)
  lazy val aliases: Set[ClusterIndexName.Remote] = aliasesNames.map(ClusterIndexName.Remote(_, clusterName))
  lazy val all: Set[ClusterIndexName.Remote] = aliases + index
}

sealed trait IndexAttribute
object IndexAttribute {
  case object Opened extends IndexAttribute
  case object Closed extends IndexAttribute
}

final case class RorConfigurationIndex(index: IndexName.Full) extends AnyVal {
  def toLocal: ClusterIndexName.Local = ClusterIndexName.Local(index)
}

final class RorAuditIndexTemplate private(nameFormatter: DateTimeFormatter,
                                          rawPattern: String) {

  def indexName(instant: Instant): IndexName.Full = {
    IndexName.Full(NonEmptyString.unsafeFrom(nameFormatter.format(instant)))
  }

  def conforms(index: IndexName): Boolean = {
    index match {
      case IndexName.Full(name) =>
        Try(nameFormatter.parse(name.value.value)).isSuccess
      case IndexName.Pattern(_) =>
        IndexName
          .fromString(rawPattern)
          .exists { i =>
            PatternsMatcher
              .create(Set(index))
              .`match`(i)
          }
    }
  }
}
object RorAuditIndexTemplate {
  val default: RorAuditIndexTemplate = from(constants.AUDIT_LOG_DEFAULT_INDEX_TEMPLATE).toOption.get

  def apply(pattern: String): Either[CreationError, RorAuditIndexTemplate] = from(pattern)

  def from(pattern: String): Either[CreationError, RorAuditIndexTemplate] = {
    Try(DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.of("UTC"))) match {
      case Success(formatter) => Right(new RorAuditIndexTemplate(formatter, pattern.replaceAll("'", "")))
      case Failure(ex) => Left(CreationError.ParsingError(ex.getMessage))
    }
  }

  sealed trait CreationError
  object CreationError {
    final case class ParsingError(msg: String) extends CreationError
  }
}
