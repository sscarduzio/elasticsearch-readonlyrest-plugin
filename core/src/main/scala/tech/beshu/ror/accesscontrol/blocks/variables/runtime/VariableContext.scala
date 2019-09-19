package tech.beshu.ror.accesscontrol.blocks.variables.runtime

import cats.data.NonEmptyList
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.MultiExtractable.SingleExtractableWrapper
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.VariableContext.Requirement.Result.Reason

object VariableContext {

  sealed trait VariableType

  object VariableType {
    trait User extends VariableType
    trait CurrentGroup extends VariableType
    trait Header extends VariableType
    trait Jwt extends VariableType
  }

  trait UsingVariable {
    this: Rule =>
    type VARIABLE <: RuntimeResolvableVariable[_]
    def uses: NonEmptyList[VARIABLE]

    def extractVariablesTypes: List[VariableType] = {
      uses.toList.flatMap {
        case v: RuntimeSingleResolvableVariable[_] => v match {
            case RuntimeSingleResolvableVariable.AlreadyResolved(_) => List.empty
            case RuntimeSingleResolvableVariable.ToBeResolved(extractables) =>
              extractables.collect { case e: VariableContext.VariableType => e }
          }
        case v: RuntimeMultiResolvableVariable[_] => v match {
          case RuntimeMultiResolvableVariable.AlreadyResolved(_) => List.empty
          case RuntimeMultiResolvableVariable.ToBeResolved(extractables) =>
            extractables.collect {
              case e: SingleExtractableWrapper if e.extractable.isInstanceOf[VariableContext.VariableType] => e.extractable.asInstanceOf[VariableContext.VariableType]
              case e: VariableContext.VariableType => e
            }
        }
      }
    }
  }

  sealed trait Requirement {
    def checkIfComplies(rulesBefore: List[Rule]): Requirement.Result
  }

  object Requirement {

    sealed trait Result
    object Result {
      case object Complied extends Result
      final case class NotComplied(reason: Reason) extends Result
      final case class Reason(value: String) extends AnyVal
    }

    case object OneOfRuleBeforeIsAuthenticationRule extends Requirement {
      override def checkIfComplies(rulesBefore: List[Rule]): Result =
        rulesBefore.collect { case rule: Rule.AuthenticationRule => rule } match {
          case Nil => Result.NotComplied(Reason("None of present rules is authentication rule"))
          case _ => Result.Complied
        }
    }

    case object OneOfRuleBeforeIsAuthorizationRule extends Requirement {
      override def checkIfComplies(rulesBefore: List[Rule]): Result =
        rulesBefore.collect { case rule: Rule.AuthorizationRule => rule } match {
          case Nil => Result.NotComplied(Reason("None of present rules is authorization rule"))
          case _ => Result.Complied
        }
    }
  }

  object RequirementChecker {

    def findRulesListedBeforeGivenRule[A <: Rule with UsingVariable](rule: A, otherRules: NonEmptyList[Rule]) =
      otherRules.toList.takeWhile(_ != rule)

    def check[A <: Rule with UsingVariable](verifiedRule: A, otherRules: NonEmptyList[Rule]) = {
      val rulesBefore = findRulesListedBeforeGivenRule(verifiedRule, otherRules)
      verifiedRule.extractVariablesTypes
        .flatMap(checkSingleVariableBasedOn(rulesBefore))
    }

    private def checkSingleVariableBasedOn (rulesBefore: List[Rule])(usedVariable: VariableType) = {
        Requirements.definedFor(usedVariable)
          .map(_.checkIfComplies(rulesBefore))
    }
  }

  object Requirements {

    def definedFor(variableType: VariableType): Option[Requirement] = {
      variableType match {
        case _: VariableType.User => Some(Requirement.OneOfRuleBeforeIsAuthenticationRule)
        case _: VariableType.CurrentGroup => Some(Requirement.OneOfRuleBeforeIsAuthorizationRule)
        case _: VariableType.Header => None
        case _: VariableType.Jwt => None
      }
    }
  }

}
