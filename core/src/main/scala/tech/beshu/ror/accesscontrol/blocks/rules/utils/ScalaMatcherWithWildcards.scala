package tech.beshu.ror.accesscontrol.blocks.rules.utils

import com.google.common.base.Strings

class ScalaMatcherWithWildcards[PATTERN: StringTNaturalTransformation](patterns: Set[PATTERN]) {

  private val nt = implicitly[StringTNaturalTransformation[PATTERN]]

  private val matchers = patterns
    .map(p => (p, nt.toAString(p).split("\\*+", -1 /* want empty trailing token if any */).toList))

  def filterWithPatternMatched[T: StringTNaturalTransformation](items: Set[T]): Set[(T, PATTERN)] = {
    items.flatMap(i => `match`(i).map((i, _)))
  }

  def filter[T: StringTNaturalTransformation](items: Set[T]): Set[T] = {
    items.flatMap(i => `match`(i).map(_ => i))
  }

  private def `match`[T: StringTNaturalTransformation](item: T): Option[PATTERN] = {
    val itemString = implicitly[StringTNaturalTransformation[T]].toAString(item)
    matchers
      .find { case (_, stringPatterns) => matchPattern(stringPatterns, itemString) }
      .map(_._1)
  }

  private def matchPattern(pattern: List[String], line: String): Boolean = {
    if (pattern.isEmpty) return Strings.isNullOrEmpty(line)
    else if (pattern.length == 1) return line == pattern.head
    if (!line.startsWith(pattern.head)) return false
    var idx = pattern.head.length
    var i = 1
    while (i < pattern.length - 1) {
      val patternTok = pattern(i)
      val nextIdx = line.indexOf(patternTok, idx)
      if (nextIdx < 0) return false
      else idx = nextIdx + patternTok.length
      i += 1
      i
    }
    line.endsWith(pattern.last)
  }

}
