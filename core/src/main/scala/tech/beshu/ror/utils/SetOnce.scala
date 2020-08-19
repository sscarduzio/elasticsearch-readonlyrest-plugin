package tech.beshu.ror.utils

import monix.execution.atomic.Atomic


class SetOnce[A] {
  private val ref: Atomic[Option[A]] = Atomic(Option.empty[A])
  def getOrElse(default: => A): A = ref.transformAndGet(_.orElse(Some(default))).get
}
