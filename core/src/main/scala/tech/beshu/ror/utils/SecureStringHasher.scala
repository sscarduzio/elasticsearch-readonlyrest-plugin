package tech.beshu.ror.utils

import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.utils.SecureStringHasher.Algorithm

class SecureStringHasher(algorithm: Algorithm) {

  private val underlying = new JavaSecureStringHasher(
    algorithm match {
      case Algorithm.Sha256 => "sha256"
      case Algorithm.Sha348 => "sha348"
      case Algorithm.Sha512 => "sha512"
    }
  )

  def hash(originalKey: NonEmptyString): NonEmptyString = {
    NonEmptyString.unsafeFrom(underlying.hash(originalKey.value))
  }
}

object SecureStringHasher {
  sealed trait Algorithm
  object Algorithm {
    case object Sha256 extends Algorithm
    case object Sha348 extends Algorithm
    case object Sha512 extends Algorithm
  }
}
