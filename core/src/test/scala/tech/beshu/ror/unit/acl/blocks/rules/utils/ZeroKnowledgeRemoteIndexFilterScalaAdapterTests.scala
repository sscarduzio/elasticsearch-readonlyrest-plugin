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
package tech.beshu.ror.unit.acl.blocks.rules.utils

import eu.timepit.refined.auto.*
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.matchers.ZeroKnowledgeRemoteIndexFilterScalaAdapter.CheckResult
import tech.beshu.ror.accesscontrol.matchers.{PatternsMatcher, ZeroKnowledgeRemoteIndexFilterScalaAdapter}
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.*

class ZeroKnowledgeRemoteIndexFilterScalaAdapterTests extends AnyWordSpec {

  "ZeroKnowledgeIndexFilter check" when {
    "one element is passed" in {
      val matcher = PatternsMatcher.create(remoteIndexName("*:a*") :: Nil)

      val res1 = new ZeroKnowledgeRemoteIndexFilterScalaAdapter()
        .check(Set(remoteIndexName("*:*")), matcher)
      res1 should be(CheckResult.Ok(Set(remoteIndexName("*:a*"))))

      val res2 = new ZeroKnowledgeRemoteIndexFilterScalaAdapter()
        .check(Set(remoteIndexName("*:b")), matcher)
      res2 should be(CheckResult.Failed)
    }
    "two elements are passed" in {
      val matcher = PatternsMatcher.create(remoteIndexName("r:a1*") :: Nil)

      val res1 = new ZeroKnowledgeRemoteIndexFilterScalaAdapter()
        .check(Set(remoteIndexName("r:a*")), matcher)
      res1 should be(CheckResult.Ok(Set(remoteIndexName("r:a1*"))))
    }
    "two patterns in matcher" in {
      val matcher = PatternsMatcher.create(remoteIndexName("b:*") :: remoteIndexName("c:a*") :: Nil)

      val res1 = new ZeroKnowledgeRemoteIndexFilterScalaAdapter()
        .check(Set(remoteIndexName("b:*")), matcher)
      res1 should be(CheckResult.Ok(Set(remoteIndexName("b:*"))))
    }
  }
}
