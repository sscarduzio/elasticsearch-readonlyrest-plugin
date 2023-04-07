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
package tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices

private[indices] object domain {

  sealed trait CanPass[+T]
  object CanPass {
    final case class Yes[T](value: T) extends CanPass[T]
    final case class No(reason: Option[No.Reason] = None) extends CanPass[Nothing]
    object No {
      def apply(reason: Reason): No = new No(Some(reason))

      sealed trait Reason
      object Reason {
        case object IndexNotExist extends Reason
      }
    }
  }

  type CheckContinuation[T] = Either[CanPass[T], Unit]
  object IndicesCheckContinuation {
    def stop[T](result: CanPass[T]): CheckContinuation[T] = Left(result)

    def continue[T]: CheckContinuation[T] = Right(())
  }
}
