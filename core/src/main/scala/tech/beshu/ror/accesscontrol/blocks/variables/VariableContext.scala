package tech.beshu.ror.accesscontrol.blocks.variables

import cats.data.NonEmptyList
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.variables.VariableContext.Requirement.Result.Reason

object VariableContext {

  sealed trait VariableType

  object VariableType {

    trait User extends VariableType
    trait CurrentGroup extends VariableType
    trait Header extends VariableType
    trait Jwt extends VariableType

  }

  trait UsingVariable {
    def uses: List[VariableType]
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

    def check[A <: Rule with UsingVariable](rule: A, otherRules: NonEmptyList[Rule]) = {
      val rulesBefore = findRulesListedBeforeGivenRule(rule, otherRules)
      val results = rule.uses
        .flatMap { usedVariable =>
          val maybeRequirement = Requirements.definedFor(usedVariable)
          val result = maybeRequirement
            .map { req =>
              req.checkIfComplies(rulesBefore)
            }
          result
        }
      results
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
