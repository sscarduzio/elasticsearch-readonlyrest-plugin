package tech.beshu.ror.es.utils

import monix.execution.atomic.Atomic
import tech.beshu.ror.es.services.EsApiKeyService

import java.util.function.Supplier

class EsApiKeyServiceSupplier extends Supplier[Option[EsApiKeyService]] {

  private val serviceRef = Atomic(Option.empty[EsApiKeyService])

  override def get(): Option[EsApiKeyService] = serviceRef.get()

  def set(service: EsApiKeyService): Unit = serviceRef.set(Some(service))

}
