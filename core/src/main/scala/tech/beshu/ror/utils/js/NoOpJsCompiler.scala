package tech.beshu.ror.utils.js
import scala.util.{Success, Try}

object NoOpJsCompiler extends JsCompiler  {
  override def compile(jsCodeString: String): Try[Unit] =
    Success(())
}
