///*
// *    This file is part of ReadonlyREST.
// *
// *    ReadonlyREST is free software: you can redistribute it and/or modify
// *    it under the terms of the GNU General Public License as published by
// *    the Free Software Foundation, either version 3 of the License, or
// *    (at your option) any later version.
// *
// *    ReadonlyREST is distributed in the hope that it will be useful,
// *    but WITHOUT ANY WARRANTY; without even the implied warranty of
// *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// *    GNU General Public License for more details.
// *
// *    You should have received a copy of the GNU General Public License
// *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
// */
//package tech.beshu.ror.accesscontrol.matchers
//
//import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, IndexName}
//import tech.beshu.ror.utils.Matchable
//
//class IndicesMatcher[T <: ClusterIndexName : Matchable](indices: Set[T]) {
//  val availableIndicesMatcher: Matcher[T] = MatcherWithWildcardsScalaAdapter[T](indices)
//
//  def filterIndices(indices: Set[T]): Set[T] = availableIndicesMatcher.filter(indices)
//
//  def `match`(value: T): Boolean = availableIndicesMatcher.`match`(value)
//
//  def contains(str: String): Boolean = availableIndicesMatcher.contains(str)
//}
//
//object IndicesMatcher {
//  def create[T <: ClusterIndexName : Matchable](indices: Set[T]): IndicesMatcher[T] = {
//    new IndicesMatcher(indices)
//  }
//}
//
//class IndicesNamesMatcher[T <: IndexName : Matchable](indices: Set[T]) {
//  val availableIndicesMatcher: Matcher[T] = MatcherWithWildcardsScalaAdapter[T](indices)
//
//  def filterIndices(indices: Set[T]): Set[T] = availableIndicesMatcher.filter(indices)
//
//  def `match`(value: T): Boolean = availableIndicesMatcher.`match`(value)
//
//  def contains(str: String): Boolean = availableIndicesMatcher.contains(str)
//}
//
//object IndicesNamesMatcher {
//  def create[T <: IndexName : Matchable](indices: Set[T]): IndicesNamesMatcher[T] = {
//    new IndicesNamesMatcher(indices)
//  }
//}

// todo: to remove?