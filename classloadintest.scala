import scala.util.{Failure, Success, Try}
trait S
final case class A(num: Int) extends S
object Testt {
  private def getMethod[S](s: => S): Int = {
    try {
     s.asInstanceOf[A].num
    } catch {
      case _: NoClassDefFoundError => 0
    }

  }

  def main(args: Array[String]): Unit = {
        println(getMethod(A(5)))

  }
}
