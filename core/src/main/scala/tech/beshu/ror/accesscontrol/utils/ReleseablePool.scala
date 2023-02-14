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
package tech.beshu.ror.accesscontrol.utils

import cats.Monad
import cats.implicits._
import monix.execution.atomic.Atomic

import scala.language.higherKinds

final class ReleseablePool[M[_] : Monad, A, B](acquire: B => M[A])(release: A => M[Unit]) {

  import ReleseablePool._

  private val pool: Atomic[ActiveList[A]] = Atomic(ActiveList[A]())

  def get(b: B): M[Either[ClosedPool.type, A]] =
    acquire(b).flatMap { resource =>
      pool.transformAndExtract(transform(resource))
    }

  private def transform(resource: A)(activeList: ActiveList[A]): (M[Either[ClosedPool.type, A]], ActiveList[A]) = {
    if (activeList.isActive) {
      (pure(resource), append(activeList, resource))
    } else {
      (close(resource), activeList)
    }
  }

  private def close(resource: A): M[Either[ReleseablePool.ClosedPool.type, A]] = {
    release(resource).map(_ => ClosedPool.asLeft)
  }

  private def pure(resource: A) =
    resource.asRight[ReleseablePool.ClosedPool.type].pure[M]

  private def append(activeList: ActiveList[A], resource: A) =
    activeList.copy(list = activeList.list :+ resource)

  def close: M[Unit] =
    pool.transformAndGet(_.copy(isActive = false))
      .list.map(release)
      .sequence
      .map(_ => ())
}

object ReleseablePool {
  private final case class ActiveList[A](isActive: Boolean = true, list: List[A] = Nil)
  case object ClosedPool
}