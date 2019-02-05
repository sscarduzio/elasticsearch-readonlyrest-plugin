package tech.beshu.ror.acl.blocks.rules.utils

import tech.beshu.ror.commons.utils.MatcherWithWildcards
import scala.collection.JavaConverters._

trait Matcher {
  def underlying: MatcherWithWildcards
  def filter[T : StringTNaturalTransformation](items: Set[T]): Set[T]
  def filter[T : StringTNaturalTransformation](remoteClusterAware: Boolean, items: Set[T]): Set[T]
  def `match`[T : StringTNaturalTransformation](value: T): Boolean
  def containsMatcher(str: String): Boolean
}

class MatcherWithWildcardsScalaAdapter(override val underlying: MatcherWithWildcards)
  extends Matcher {

  override def filter[T : StringTNaturalTransformation](remoteClusterAware: Boolean, items: Set[T]): Set[T] = {
    val nt = implicitly[StringTNaturalTransformation[T]]
    underlying
      .filter(remoteClusterAware, items.map(nt.toAString(_)).asJava)
      .asScala
      .map(nt.fromString)
      .toSet
  }

  override def filter[T: StringTNaturalTransformation](items: Set[T]): Set[T] = {
    val nt = implicitly[StringTNaturalTransformation[T]]
    underlying
      .filter(items.map(nt.toAString(_)).asJava)
      .asScala
      .map(nt.fromString)
      .toSet
  }

  override def `match`[T: StringTNaturalTransformation](value: T): Boolean = {
    val nt = implicitly[StringTNaturalTransformation[T]]
    underlying.`match`(nt.toAString(value))
  }

  override def containsMatcher(str: String): Boolean =
    underlying.getMatchers.contains(str)
}

final case class StringTNaturalTransformation[T](fromString: String => T, toAString: T => String)