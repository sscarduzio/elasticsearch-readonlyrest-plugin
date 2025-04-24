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
package tech.beshu.ror.accesscontrol.blocks.definitions.user

import cats.data.NonEmptyList
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef
import tech.beshu.ror.accesscontrol.blocks.definitions.user.UserDefinitionsValidator.ValidationError
import tech.beshu.ror.accesscontrol.domain.User
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class UserDefinitionsValidator(globalSettings: GlobalSettings) {

  def validate(definitions: Definitions[UserDef]): Either[NonEmptyList[ValidationError], Unit] = {
    if(globalSettings.usersDefinitionDuplicateUsernamesValidationEnabled) {
      validateNonDuplicatedUsernames(definitions)
    } else {
      Right(())
    }
  }

  private def validateNonDuplicatedUsernames(definitions: Definitions[UserDef]): Either[NonEmptyList[ValidationError], Unit] = {
    val localUsersPerUserDefinition: List[UniqueNonEmptyList[User.Id]] =
      definitions
        .items
        .flatMap { definition =>
          UniqueNonEmptyList.from(definition.usernames.patterns.filterNot(_.containsWildcard).map(_.value))
        }

    val validationErrors =
      localUsersPerUserDefinition
        .flatten
        .groupBy(identity)
        .filter {
          case (_, userIdOccurrences) => userIdOccurrences.length > 1
        }
        .keys
        .map { userId =>
          ValidationError.DuplicatedUsernameForLocalUser(userId)
        }
        .toList

    NonEmptyList.fromList(validationErrors).toLeft(())
  }

}
object UserDefinitionsValidator {

  sealed trait ValidationError

  object ValidationError {
    final case class DuplicatedUsernameForLocalUser(userId: User.Id) extends ValidationError
  }

}