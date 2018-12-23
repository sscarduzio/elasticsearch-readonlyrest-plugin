package tech.beshu.ror.acl.blocks

import org.scalamock.scalatest.MockFactory
import org.scalatest.WordSpec
import tech.beshu.ror.acl.blocks.Variable.{ResolvedValue, ValueWithVariable}
import tech.beshu.ror.mocks.MockRequestContext
import org.scalatest.Matchers._
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.commons.aDomain.Header
import tech.beshu.ror.commons.domain.{LoggedUser, User}

class VariablesManagerTests extends WordSpec with MockFactory {

  "A VariablesManager" should {
    "resolve variable" when {
      "given variable has corresponding header in request context" in {
        val (vm, blockContext) = createVariablesManager(MockRequestContext(headers = Set(Header("key1" -> "x"))), None)
        vm.resolve(ValueWithVariable("@{key1}"), blockContext) should be(Some(ResolvedValue("x")))
      }
      "given variable has corresponding header in request context but upper-case" in {
        val (vm, blockContext) = createVariablesManager(MockRequestContext(headers = Set(Header("KEY1" -> "x"))), None)
        vm.resolve(ValueWithVariable("@{key1}"), blockContext) should be(Some(ResolvedValue("x")))
      }
      "user variable is used and there is logged user" in {
        val (vm, blockContext) = createVariablesManager(MockRequestContext.default, Some(LoggedUser(User.Id("simone"))))
        vm.resolve(ValueWithVariable("@{user}"), blockContext) should be(Some(ResolvedValue("simone")))
      }
      "@ is used as usual char" in {
        val (vm, blockContext) = createVariablesManager(MockRequestContext(headers = Set(Header("KEY1" -> "x"))), Some(LoggedUser(User.Id("simone"))))
        vm.resolve(ValueWithVariable("@@@@{key1}"), blockContext) should be(Some(ResolvedValue("@@@x")))
        vm.resolve(ValueWithVariable("@one@two@{key1}@three@@@"), blockContext) should be(Some(ResolvedValue("@one@twox@three@@@")))
        vm.resolve(ValueWithVariable(".@one@two.@{key1}@three@@@"), blockContext) should be(Some(ResolvedValue(".@one@two.x@three@@@")))
      }
    }
    "not resolve variable" when {
      "given variable doesn't have corresponding header in request context" in {
        val (vm, blockContext) = createVariablesManager(MockRequestContext(headers = Set(Header("key2" -> "x"))), None)
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
