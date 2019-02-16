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
package tech.beshu.ror.unit.acl.blocks

import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.TestsUtils._
import tech.beshu.ror.acl.aDomain.{LoggedUser, User}
import tech.beshu.ror.acl.blocks.Variable.{ResolvedValue, ValueWithVariable}
import tech.beshu.ror.acl.blocks.{BlockContext, VariablesManager}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.mocks.MockRequestContext

class VariablesManagerTests extends WordSpec with MockFactory {

  "A VariablesManager" should {
    "resolve variable" when {
      "given variable has corresponding header in request context" in {
        val (vm, blockContext) = createVariablesManager(MockRequestContext(headers = Set(headerFrom("key1" -> "x"))), None)
        vm.resolve(ValueWithVariable("@{key1}"), blockContext) should be(Some(ResolvedValue("x")))
      }
      "given variable has corresponding header in request context but upper-case" in {
        val (vm, blockContext) = createVariablesManager(MockRequestContext(headers = Set(headerFrom("KEY1" -> "x"))), None)
        vm.resolve(ValueWithVariable("@{key1}"), blockContext) should be(Some(ResolvedValue("x")))
      }
      "user variable is used and there is logged user" in {
        val (vm, blockContext) = createVariablesManager(MockRequestContext.default, Some(LoggedUser(User.Id("simone"))))
        vm.resolve(ValueWithVariable("@{user}"), blockContext) should be(Some(ResolvedValue("simone")))
      }
      "@ is used as usual char" in {
        val (vm, blockContext) = createVariablesManager(MockRequestContext(headers = Set(headerFrom("KEY1" -> "x"))), Some(LoggedUser(User.Id("simone"))))
        vm.resolve(ValueWithVariable("@@@@{key1}"), blockContext) should be(Some(ResolvedValue("@@@x")))
        vm.resolve(ValueWithVariable("@one@two@{key1}@three@@@"), blockContext) should be(Some(ResolvedValue("@one@twox@three@@@")))
        vm.resolve(ValueWithVariable(".@one@two.@{key1}@three@@@"), blockContext) should be(Some(ResolvedValue(".@one@two.x@three@@@")))
      }
    }
    "not resolve variable" when {
      "given variable doesn't have corresponding header in request context" in {
        val (vm, blockContext) = createVariablesManager(MockRequestContext(headers = Set(headerFrom("key2" -> "x"))), None)
        vm.resolve(ValueWithVariable("@{key1}"), blockContext) should be(None)
      }
      "user variable is used but there is no logged user" in {
        val (vm, blockContext) = createVariablesManager(MockRequestContext.default, None)
        vm.resolve(ValueWithVariable("@{user}"), blockContext) should be(None)
      }
    }
  }

  private def createVariablesManager(requestContext: RequestContext, loggedUser: Option[LoggedUser]) = {
    val vm = new VariablesManager(requestContext)
    val blockContext = mock[BlockContext]
    (blockContext.loggedUser _).expects().atLeastOnce().returning(loggedUser)
    (vm, blockContext)
  }
}
