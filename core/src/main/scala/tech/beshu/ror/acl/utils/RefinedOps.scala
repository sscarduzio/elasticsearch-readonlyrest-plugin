package tech.beshu.ror.acl.utils

import eu.timepit.refined.types.string.NonEmptyString

object RefinedOps {

  implicit def nonEmptyStringToString(str: NonEmptyString): String = str.value
}
