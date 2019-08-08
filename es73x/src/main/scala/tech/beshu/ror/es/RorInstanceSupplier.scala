package tech.beshu.ror.es

import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier

import tech.beshu.ror.boot.RorInstance

object RorInstanceSupplier extends Supplier[Option[RorInstance]]{
  private val rorInstanceAtomicReference = new AtomicReference(Option.empty[RorInstance])

  override def get(): Option[RorInstance] = rorInstanceAtomicReference.get()

  def update(rorInstance: RorInstance): Unit = {
    rorInstanceAtomicReference.set(Some(rorInstance))
  }
}
