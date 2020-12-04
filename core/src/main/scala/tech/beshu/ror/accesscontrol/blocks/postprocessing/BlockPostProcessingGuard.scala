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
package tech.beshu.ror.accesscontrol.blocks.postprocessing

import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.postprocessing.BlockPostProcessingGuard.{Name, PostProcessingResult}
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}

trait BlockPostProcessingGuard {

  def name: Name
  def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[PostProcessingResult]
}

object BlockPostProcessingGuard {

  final case class Name(value: NonEmptyString)

  sealed trait PostProcessingResult
  object PostProcessingResult {
    case object Continue extends PostProcessingResult
    case object Reject extends PostProcessingResult
  }
}