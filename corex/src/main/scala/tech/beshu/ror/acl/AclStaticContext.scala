package tech.beshu.ror.acl

import cats.data.NonEmptyList
import tech.beshu.ror.acl.blocks.Block
import tech.beshu.ror.acl.blocks.rules.{FieldsRule, FilterRule}
import tech.beshu.ror.acl.blocks.rules.Rule.{AuthenticationRule, AuthorizationRule}

class AclStaticContext(blocks: NonEmptyList[Block],
                       showBasicAuthPrompt: Boolean) {

  val involvesFilter: Boolean = {
    blocks
      .find(_
        .rules
        .collect {
          case _: FilterRule => true
          case _: FieldsRule => true
        }
        .nonEmpty
      )
      .isDefined
  }

  val doesRequirePassword: Boolean = {
    showBasicAuthPrompt &&
      blocks
        .find(_
          .rules
          .collect {
            case _: AuthenticationRule => true
            case _: AuthorizationRule => true
          }
          .nonEmpty
        )
        .isDefined
  }
}
