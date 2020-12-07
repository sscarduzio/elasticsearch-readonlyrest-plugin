//package tech.beshu.ror.proxy.es.proxyaction.indices
//
//import org.elasticsearch.action.ActionType
//import org.elasticsearch.common.io.stream.Writeable
//
//class GenericPathIndicesAction
//  extends ActionType[GenericPathIndicesResponse](
//    GenericPathIndicesAction.NAME,
//    GenericPathIndicesAction.exceptionReader
//  )
//
//object GenericPathIndicesAction {
//
//  val NAME = "proxy:pathindices"
//  val INSTANCE: ActionType[GenericPathIndicesResponse] = new GenericPathIndicesAction
//
//  def exceptionReader[A]: Writeable.Reader[A] = _ => throw new IllegalStateException("ROR proxy doesn't support transporting")
//}