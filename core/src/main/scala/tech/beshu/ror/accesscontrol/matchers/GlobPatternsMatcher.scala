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
package tech.beshu.ror.accesscontrol.matchers

import com.hrakaroo.glob.{GlobPattern, MatchingEngine}
import tech.beshu.ror.accesscontrol.domain.CaseSensitivity
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher.Matchable
import tech.beshu.ror.syntax.*

private[matchers] class GlobPatternsMatcher[A: Matchable](val values: Iterable[A])
  extends PatternsMatcher[A] {

  import GlobPatternsMatcher.*

  private val matchable: Matchable[A] = implicitly[Matchable[A]]

  override val caseSensitivity: CaseSensitivity = matchable.caseSensitivity
  override val patterns: Iterable[String] = values.map(matchable.show)

  private val ignoreCase: Boolean = caseSensitivity == CaseSensitivity.Disabled
  private val compiled: Compiled = Compiled.from(patterns, ignoreCase, globPatternFlags(caseSensitivity))

  override def `match`[B <: A](value: B): Boolean = {
    if (compiled.matchAll) true
    else {
      val raw = matchable.show(value)
      val norm = if (ignoreCase) raw.toLowerCase else raw
      compiled.exact.contains(norm) ||
        compiled.prefixes.exists(norm.startsWith) ||
        compiled.suffixes.exists(norm.endsWith) ||
        compiled.infixes.exists(norm.contains) ||
        compiled.complex.exists(_.matches(raw))
    }
  }

  override def `match`[B: Conversion](value: B): Boolean = {
    val conv = implicitly[Conversion[B]]
    `match`(conv(value))
  }

  override def filter[B <: A](items: IterableOnce[B]): Set[B] =
    filterWith(items, identity)

  override def filter[B: Conversion](items: IterableOnce[B]): Set[B] =
    filterWith(items, implicitly[Conversion[B]])

  override def contains(str: String): Boolean = patterns.exists(_ == str)

  private def filterWith[B](items: IterableOnce[B], conversion: Conversion[B]): Set[B] = {
    if (compiled.matchAll) items.toCovariantSet
    else items.iterator.filter(b => `match`(conversion(b))).toCovariantSet
  }

  private def globPatternFlags(cs: CaseSensitivity): Int = cs match {
    case CaseSensitivity.Enabled  => 0
    case CaseSensitivity.Disabled => GlobPattern.CASE_INSENSITIVE
  }
}

private[matchers] object GlobPatternsMatcher {

  private final case class Compiled(matchAll: Boolean,
                                    exact: Set[String],
                                    prefixes: Array[String],
                                    suffixes: Array[String],
                                    infixes: Array[String],
                                    complex: Array[MatchingEngine])

  private object Compiled {
    def from(patterns: Iterable[String], ignoreCase: Boolean, globFlags: Int): Compiled = {
      def norm(s: String) = if (ignoreCase) s.toLowerCase else s
      val kinds = patterns.iterator.map(Kind.of).toVector
      Compiled(
        matchAll = kinds.contains(Kind.All),
        exact    = kinds.collect { case Kind.Exact(p)   => norm(p) }.toCovariantSet,
        prefixes = kinds.collect { case Kind.Prefix(p)  => norm(p) }.toArray,
        suffixes = kinds.collect { case Kind.Suffix(p)  => norm(p) }.toArray,
        infixes  = kinds.collect { case Kind.Infix(p)   => norm(p) }.toArray,
        complex  = kinds.collect { case Kind.Complex(p) => GlobPattern.compile(p, '*', '?', globFlags) }.toArray
      )
    }
  }

  // Structural classification of a glob pattern. Pure prefix/suffix/infix shapes
  // can be matched with String.startsWith / endsWith / contains; only Complex
  // patterns need the glob engine.
  private enum Kind {
    case All
    case Exact(pattern: String)
    case Prefix(pattern: String)
    case Suffix(pattern: String)
    case Infix(pattern: String)
    case Complex(pattern: String)
  }

  private object Kind {
    def of(p: String): Kind = p match {
      case "*" =>
        All
      case s if !hasWildcard(s) =>
        Exact(s)
      case s if s.length > 1 && !s.contains('?') && s.indexOf('*') == s.length - 1 =>
        Prefix(s.dropRight(1))
      case s if s.length > 1 && !s.contains('?') && s.charAt(0) == '*' && s.indexOf('*', 1) < 0 =>
        Suffix(s.tail)
      case s if s.length > 2 && !s.contains('?')
                && s.charAt(0) == '*' && s.charAt(s.length - 1) == '*'
                && s.indexOf('*', 1) == s.length - 1 =>
        Infix(s.substring(1, s.length - 1))
      case s =>
        Complex(s)
    }

    private def hasWildcard(s: String): Boolean = s.contains('*') || s.contains('?')
  }
}
