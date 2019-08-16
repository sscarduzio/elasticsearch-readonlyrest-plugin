package tech.beshu.ror.acl.blocks.rules.utils


import com.google.common.base.Strings

class ScalaMatcherWithWildcards[T : Pattern](item: Iterable[T]) {
  private val patternsList = item.flatMap { i =>
    implicitly[Pattern[T]]
      .from(i)
      .map(_.split("\\*+", -1 /* want empty trailing token if any */).toList)
      .map((i, _))
  }

  def filter(items: Set[String]): Set[String] = {
    if(items.isEmpty) Set.empty
    else items.filter(`match`)
  }

//  def filter(items: Set[T]): Set[T] = {
//    if(items.isEmpty) Set.empty
//    else items.filter(`match`)
//  }

  def `match`(haystack: String): Boolean = {
    for (p <- patternsList) {
      if (miniglob(p._2, haystack)) return true
    }
    false
  }

  def `match`(item: T): Boolean = {
    for (ip <- implicitly[Pattern[T]].from(item)) {
      if (`match`(ip)) return true
    }
    false
  }

  private def miniglob(pattern: List[String], line: String): Boolean = {
    if (pattern.isEmpty) return Strings.isNullOrEmpty(line)
    else if (pattern.size == 1) return line == pattern.head
    if (!line.startsWith(pattern.head)) return false
    pattern.tail.foldLeft(pattern.head.length) {
      case (idx, patternTok) =>
        val nextIdx = line.indexOf(patternTok, idx)
        if(nextIdx < 0) return false
        else nextIdx + patternTok.length()
    }
    line.endsWith(pattern.last)
  }
}

trait Pattern[T] {
  def from(item: T): Set[String]
}
