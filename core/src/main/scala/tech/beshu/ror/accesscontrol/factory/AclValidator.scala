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
package tech.beshu.ror.accesscontrol.factory

import cats.data.{NonEmptyList, ValidatedNel}
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BaseGroupsRule
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.{Attributes, BlockDecodingResult}
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.*
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.ScalaOps.findDuplicates

object AclValidator {

  def validate(blockDecodingResultsOpt: Option[List[BlockDecodingResult]],
               userDefs: Definitions[UserDef]): ValidatedNel[String, NonEmptyList[BlockDecodingResult]] = {
    lazy val blocks = blockDecodingResultsOpt.getOrElse(List.empty).map(_.block)
    (
      validateAclPresentAndNonEmpty(blockDecodingResultsOpt),
      validateThereAreNoBlockDuplicates(blocks),
      validateThatUsersConfigSectionIsUsedWhenPresent(blocks, userDefs),
    ).mapN { case (blocksNel, _, _) => blocksNel }
  }

  private def validateAclPresentAndNonEmpty(blockDecodingResultsOpt: Option[List[BlockDecodingResult]]): ValidatedNel[String, NonEmptyList[BlockDecodingResult]] =
    blockDecodingResultsOpt match {
      case None =>
        s"No ${Attributes.acl} section found".invalidNel
      case Some(blocks) =>
        NonEmptyList.fromList(blocks) match {
          case None => s"${Attributes.acl} defined, but no block found".invalidNel
          case Some(blocksNel) => blocksNel.validNel
        }
    }

  private def validateThereAreNoBlockDuplicates(blocks: List[Block]): ValidatedNel[String, Unit] =
    blocks.map(_.name).findDuplicates match {
      case Nil => ().validNel
      case duplicates => s"Blocks must have unique names. Duplicates: ${duplicates.show}".invalidNel
    }

  private def validateThatUsersConfigSectionIsUsedWhenPresent(blocks: List[Block],
                                                              userDefs: Definitions[UserDef]): ValidatedNel[String, Unit] = {
    val thereAreUserDefinitions = userDefs.items.nonEmpty
    lazy val thereIsGroupsRule = blocks.flatMap(_.rules.toList).collect {
      case rule: BaseGroupsRule[_] => rule
    }.nonEmpty

    if (thereAreUserDefinitions && !thereIsGroupsRule) {
      s"The `${UsersDefinitionsDecoder.usersKey}` config section is defined, but there is no groups rule that uses it. Either remove the `${UsersDefinitionsDecoder.usersKey}` section in the config, or add the groups rule in the ACL.".invalidNel
    } else {
      ().validNel
    }
  }

}
