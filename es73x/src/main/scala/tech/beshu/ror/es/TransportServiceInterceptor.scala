package tech.beshu.ror.es

import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier

import org.elasticsearch.common.component.AbstractLifecycleComponent
import org.elasticsearch.common.inject.Inject
import org.elasticsearch.transport.{RemoteClusterService, TransportService}

class TransportServiceInterceptor(transportService: TransportService,
                                  ignore: Unit) // hack!
  extends AbstractLifecycleComponent() {

  @Inject
  def this(transportService: TransportService) {
    this(transportService, ())
  }
  Option(transportService.getRemoteClusterService).foreach { r: RemoteClusterService =>
    TransportServiceInterceptor.remoteClusterServiceSupplier.update(r)
  }
  override def doStart(): Unit = {}
  override def doStop(): Unit = {}
  override def doClose(): Unit = {}
}
object TransportServiceInterceptor {
  val remoteClusterServiceSupplier: RemoteClusterServiceSupplier = new RemoteClusterServiceSupplier
}

class RemoteClusterServiceSupplier extends Supplier[Option[RemoteClusterService]] {
  private val remoteClusterServiceAtomicReference = new AtomicReference(Option.empty[RemoteClusterService])
  override def get(): Option[RemoteClusterService] = remoteClusterServiceAtomicReference.get() match {
    case Some(value) => Option(value)
    case None => Option.empty
  }
  def update(service: RemoteClusterService): Unit = remoteClusterServiceAtomicReference.set(Some(service))
}
