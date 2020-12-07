///*
// *     Beshu Limited all rights reserved
// */
//package tech.beshu.ror.proxy.es.proxyaction.generic
//
//import org.elasticsearch.action.ActionType
//import org.elasticsearch.common.io.stream.Writeable
//
//class GenericAction
//  extends ActionType[GenericResponse](
//    GenericAction.NAME,
//    GenericAction.exceptionReader
//  )
//
//object GenericAction {
//
//  val NAME = "proxy:generic"
//  val INSTANCE: ActionType[GenericResponse] = new GenericAction
//
//  def exceptionReader[A]: Writeable.Reader[A] = _ => throw new IllegalStateException("ROR proxy doesn't support transporting")
//}