package tech.beshu.ror.accesscontrol.blocks

import tech.beshu.ror.accesscontrol.domain.ResponseFieldsFiltering._
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

trait ResponseTransformation

final case class FilteredResponseFields(responseFields: UniqueNonEmptyList[ResponseField], accessMode: AccessMode) extends ResponseTransformation
