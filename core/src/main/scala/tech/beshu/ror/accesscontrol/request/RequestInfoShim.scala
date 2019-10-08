package tech.beshu.ror.accesscontrol.request

import cats.{Monad, StackSafeMonad}
import tech.beshu.ror.accesscontrol.blocks.rules.utils.MatcherWithWildcardsScalaAdapter
import tech.beshu.ror.accesscontrol.blocks.rules.utils.StringTNaturalTransformation.instances.identityNT
import tech.beshu.ror.accesscontrol.request.RequestInfoShim.WriteResult

trait RequestInfoShim {
  def extractType: String

  def getExpandedIndices(ixsSet: Set[String]): Set[String] = {
    if(!involvesIndices) {
      throw new IllegalArgumentException("can'g expand indices of a request that does not involve indices: " + extractAction)
    }
    val all = extractAllIndicesAndAliases
      .flatMap { case (indexName, aliases) =>
        aliases + indexName
      }
    MatcherWithWildcardsScalaAdapter.create(ixsSet).filter(all)
  }

  def extractIndexMetadata(index: String): Set[String]

  def extractTaskId: Long

  def extractContentLength: Integer

  def extractContent: String

  def extractMethod: String

  def extractURI: String

  def extractIndices: Set[String]

  def extractSnapshots: Set[String]

  def extractRepositories: Set[String]

  def extractAction: String

  def extractRequestHeaders: Map[String, String]

  def extractRemoteAddress: String

  def extractLocalAddress: String

  def extractId: String

  def extractAllIndicesAndAliases: Set[(String, Set[String])]

  def extractTemplateIndicesPatterns: Set[String]

  def extractIsReadRequest: Boolean

  def extractIsAllowedForDLS: Boolean

  def extractIsCompositeRequest: Boolean

  def extractHasRemoteClusters: Boolean

  def involvesIndices: Boolean

  def writeIndices(newIndices: Set[String]): WriteResult[Unit]

  def writeRepositories(newRepositories: Set[String]): WriteResult[Unit]

  def writeSnapshots(newSnapshots: Set[String]): WriteResult[Unit]

  def writeResponseHeaders(hMap: Map[String, String]): WriteResult[Unit]

  def writeToThreadContextHeaders(hMap: Map[String, String]): WriteResult[Unit]
}

object RequestInfoShim {

  sealed trait WriteResult[+T]
  object WriteResult {
    final case class Success[T](value: T) extends WriteResult[T]
    case object Failure extends WriteResult[Nothing]

    implicit val monad: Monad[WriteResult] = new StackSafeMonad[WriteResult] {
      override def flatMap[A, B](fa: WriteResult[A])(f: A => WriteResult[B]): WriteResult[B] = fa match {
        case Success(value) => f(value)
        case Failure => Failure
      }

      override def pure[A](x: A): WriteResult[A] = Success(x)
    }
  }
}