package tech.beshu.ror.accesscontrol.blocks

import tech.beshu.ror.accesscontrol.domain.ResponseFieldsFiltering._
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

sealed trait ResponseTransformation

final case class FilteredResponseFields(responseFieldsRestrictions: ResponseFieldsRestrictions) extends ResponseTransformation
