/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.tools

import os.Path
import scopt.{DefaultOEffectSetup, OParser}
import tech.beshu.ror.tools.RorToolsAppHandler.Result
import tech.beshu.ror.tools.core.actions.*
import tech.beshu.ror.tools.core.patches.base.EsPatch
import tech.beshu.ror.tools.core.utils.InOut.ConsoleInOut
import tech.beshu.ror.tools.core.utils.{EsDirectory, InOut, RorToolsException}

import scala.util.{Failure, Success, Try}

object RorToolsApp {

  // todo:
  // 1. option: return success when already patched/unpatched
  // 2. restore backup when fails to patch
  def main(args: Array[String]): Unit = {
    RorToolsAppHandler.handle(args)(ConsoleInOut) match {
      case Result.Success =>
        ()
      case Result.Failure(exitCode) =>
        // The details of why the command was not parsed should have been
        // already printed by the scopt library parser
        sys.exit(exitCode)
      case Result.CommandNotParsed =>
        sys.exit(1)
    }
  }

}

object RorToolsAppHandler {

  def handle(args: Array[String])(implicit inOut: InOut): Result = {
    OParser.runParser(
      parser,
      args.map(arg => if (arg.startsWith("--")) arg.toLowerCase else arg),
      Arguments(Command.Verify(None), UserUnderstandsAndAcceptsESPatching.AnswerNotGiven)
    ) match {
      case (result, effects) =>
        OParser.runEffects(effects, new DefaultOEffectSetup {
          override def displayToOut(msg: String): Unit = inOut.println(msg)

          override def displayToErr(msg: String): Unit = inOut.printlnErr(msg)
        })
        result match {
          case None =>
            Result.CommandNotParsed
          case Some(config) =>
            Try {
              config.command match {
                case command: Command.Patch =>
                  new PatchCommandHandler().handle(command, config)
                case command: Command.Unpatch =>
                  new UnpatchCommandHandler().handle(command)
                case command: Command.Verify =>
                  new VerifyCommandHandler().handle(command)
              }
            } match {
              case Failure(ex: RorToolsException) =>
                inOut.println(s"ERROR: ${ex.getMessage()}\n${ex.printStackTrace()}")
                Result.Failure(1)
              case Failure(ex: Throwable) =>
                inOut.println(s"UNEXPECTED ERROR: ${ex.getMessage()}\n${ex.printStackTrace()}")
                Result.Failure(1)
              case Success(result) =>
                result
            }
        }
    }
  }

  private class PatchCommandHandler(implicit inOut: InOut) {

    import tech.beshu.ror.tools.RorToolsAppHandler.UserUnderstandsAndAcceptsESPatching.*

    def handle(command: Command.Patch,
               config: Arguments): Result = {
      config.userUnderstandsImplicationsOfESPatching match {
        case Yes =>
          performPatching(command.customEsPath)
        case No =>
          patchingAbortedBecauseUserDidNotAcceptConsequences()
        case AnswerNotGiven =>
          if (userConfirmsUnderstandingOfTheESPatchingImplications()) {
            performPatching(command.customEsPath)
          } else {
            patchingAbortedBecauseUserDidNotAcceptConsequences()
          }
      }
    }

    private def performPatching(customESPath: Option[Path]): Result = {
      val esDirectory = esDirectoryFrom(customESPath)
      new PatchAction(EsPatch.create(esDirectory)).execute()
      Result.Success
    }

    private def patchingAbortedBecauseUserDidNotAcceptConsequences(): Result = {
      inOut.println("You have to confirm, that You understand the implications of ES patching in order to perform it.\nYou can read about patching in our documentation: https://docs.readonlyrest.com/elasticsearch#id-3.-patch-elasticsearch.")
      Result.Failure(1)
    }

    private def userConfirmsUnderstandingOfTheESPatchingImplications(): Boolean = {
      inOut.println("Elasticsearch needs to be patched to work with ReadonlyREST. You can read about patching in our documentation: https://docs.readonlyrest.com/elasticsearch#id-3.-patch-elasticsearch.")
      inOut.print("Do you understand the implications of ES patching? (yes/no): ")
      inOut.readLine().toLowerCase match
        case "yes" => true
        case _ => false
    }

  }

  private class UnpatchCommandHandler(implicit inOut: InOut) {
    def handle(command: Command.Unpatch): Result = {
      val esDirectory = esDirectoryFrom(command.customEsPath)
      new UnpatchAction(EsPatch.create(esDirectory)).execute()
      Result.Success
    }
  }

  private class VerifyCommandHandler(implicit inOut: InOut) {
    def handle(command: Command.Verify): Result = {
      val esDirectory = esDirectoryFrom(command.customEsPath)
      new VerifyAction(EsPatch.create(esDirectory)).execute()
      Result.Success
    }
  }


  private def esDirectoryFrom(esPath: Option[os.Path]) = {
    esPath.map(EsDirectory.from).getOrElse(EsDirectory.default)
  }

  private val builder = OParser.builder[Arguments]

  import builder.*

  private lazy val parser = OParser.sequence(
    head("ROR tools", "1.0.0"),
    programName("java -jar ror-tools.jar"),
    patchCommand,
    note(""),
    opt[String]("i-understand-and-accept-es-patching").optional()
      .valueName("<yes/no>")
      .validate {
        case "yes" => success
        case "no" => success
        case other => failure(s"ERROR: Invalid value [$other]. Only values 'yes' and 'no' can be provided as an answer.")
      }
      .action { (answer, config) =>
        answer.toLowerCase match {
          case "yes" => config.copy(userUnderstandsImplicationsOfESPatching = UserUnderstandsAndAcceptsESPatching.Yes)
          case "no" => config.copy(userUnderstandsImplicationsOfESPatching = UserUnderstandsAndAcceptsESPatching.No)
        }
      }
      .text("Optional, when provided with value 'yes', it confirms that the user understands and accepts the implications of ES patching. The patching can therefore be performed. When not provided, user will be asked for confirmation in interactive mode."),
    unpatchCommand,
    note(""),
    verifyCommand,
    note(""),
    help('h', "help").text("prints this usage text")
  )

  private lazy val patchCommand =
    cmd("patch")
      .action((_, c) => c.copy(command = Command.Patch(None)))
      .text("patch is a command that modifies ES installation for ROR purposes")
      .children(
        esPathOption
          .action((p, c) => c.copy(command = c.command.asInstanceOf[Command.Patch].copy(customEsPath = Some(os.Path(p)))))
      )

  private lazy val unpatchCommand =
    cmd("unpatch")
      .action((_, c) => c.copy(command = Command.Unpatch(None)))
      .text("unpatch is a command that reverts modifications done by patching")
      .children(
        esPathOption
          .action((p, c) => c.copy(command = c.command.asInstanceOf[Command.Unpatch].copy(customEsPath = Some(os.Path(p)))))
      )

  private lazy val verifyCommand =
    cmd("verify")
      .action((_, c) => c.copy(command = Command.Verify(None)))
      .text("verify is a command that verifies if ES installation is patched")
      .children(
        esPathOption
          .action((p, c) => c.copy(command = c.command.asInstanceOf[Command.Verify].copy(customEsPath = Some(os.Path(p)))))
      )

  private lazy val esPathOption =
    opt[String]("es-path")
      .text(s"Path to elasticsearch directory; default=${EsDirectory.defaultPath}")
      .validate { path =>
        Try(os.Path(path))
          .toEither
          .flatMap { p => Either.cond(os.exists(p), (), ()) }
          .left.map(_ => s"Path [$path] does not exist")
      }

  private final case class Arguments(command: Command,
                                     userUnderstandsImplicationsOfESPatching: UserUnderstandsAndAcceptsESPatching)

  private sealed trait Command

  private object Command {
    final case class Patch(customEsPath: Option[os.Path]) extends Command
    final case class Unpatch(customEsPath: Option[os.Path]) extends Command
    final case class Verify(customEsPath: Option[os.Path]) extends Command
  }

  private sealed trait UserUnderstandsAndAcceptsESPatching

  private object UserUnderstandsAndAcceptsESPatching {
    case object Yes extends UserUnderstandsAndAcceptsESPatching
    case object No extends UserUnderstandsAndAcceptsESPatching
    case object AnswerNotGiven extends UserUnderstandsAndAcceptsESPatching
  }

  sealed trait Result

  object Result {
    case object CommandNotParsed extends Result
    case object Success extends Result
    final case class Failure(exitCode: Int) extends Result
  }

}
