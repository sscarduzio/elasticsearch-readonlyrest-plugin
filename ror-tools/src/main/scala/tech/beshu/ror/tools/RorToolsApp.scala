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
import scopt.OParser
import tech.beshu.ror.tools.core.actions.*
import tech.beshu.ror.tools.core.patches.base.EsPatch
import tech.beshu.ror.tools.core.utils.{EsDirectory, RorToolsException}

import scala.io.StdIn
import scala.util.{Failure, Success, Try}

object RorToolsApp {

  // todo:
  // 1. option: return success when already patched/unpatched
  // 2. restore backup when fails to patch
  def main(args: Array[String]): Unit = {
    handle(args) match {
      case Result.CommandNotParsed => ()
      case Result.Success => ()
      case Result.Failure(exitCode) => sys.exit(exitCode)
    }
  }

  def handle(args: Array[String]): Result = {
    OParser.parse(parser, args, Config(Command.Verify(None), UserUnderstandsImplicationsOfESPatching.AnswerNotGiven)) match {
      case None =>
        Result.CommandNotParsed
      case Some(config) =>
        Try {
          config.command match {
            case Command.Patch(customEsPath) =>
              config.userUnderstandsImplicationsOfESPatching match {
                case UserUnderstandsImplicationsOfESPatching.Yes =>
                  performPatching(customEsPath)
                case UserUnderstandsImplicationsOfESPatching.No =>
                  patchingAbortedBecauseUserDidNotAcceptConsequences()
                case UserUnderstandsImplicationsOfESPatching.AnswerNotGiven =>
                  if (askForConfirmation() == "yes") performPatching(customEsPath)
                  else patchingAbortedBecauseUserDidNotAcceptConsequences()
              }
            case Command.Unpatch(customEsPath) =>
              val esDirectory = esDirectoryFrom(customEsPath)
              new UnpatchAction(EsPatch.create(esDirectory)).execute()
              Result.Success
            case Command.Verify(customEsPath) =>
              val esDirectory = esDirectoryFrom(customEsPath)
              new VerifyAction(EsPatch.create(esDirectory)).execute()
              Result.Success
          }
        } match {
          case Failure(ex: RorToolsException) =>
            println(s"ERROR: ${ex.getMessage()}\n${ex.printStackTrace()}")
            Result.Failure(1)
          case Failure(ex: Throwable) =>
            println(s"UNEXPECTED ERROR: ${ex.getMessage()}\n${ex.printStackTrace()}")
            Result.Failure(1)
          case Success(result) =>
            result
        }
    }
  }

  private def patchingAbortedBecauseUserDidNotAcceptConsequences(): Result = {
    println("You have to confirm, that You understand the implications of ES patching in order to perform it.\nYou can read about patching in our documentation: https://docs.readonlyrest.com/elasticsearch#id-3.-patch-elasticsearch.")
    Result.Failure(1)
  }

  private def askForConfirmation(): String = {
    println("Elasticsearch needs to be patched to work with ReadonlyREST. You can read about patching in our documentation: https://docs.readonlyrest.com/elasticsearch#id-3.-patch-elasticsearch.")
    print("Do you understand the implications of ES patching? (yes/no): ")
    StdIn.readLine()
  }

  private def performPatching(customESPath: Option[Path]): Result = {
    val esDirectory = esDirectoryFrom(customESPath)
    new PatchAction(EsPatch.create(esDirectory)).execute()
    Result.Success
  }

  private def esDirectoryFrom(esPath: Option[os.Path]) = {
    esPath.map(EsDirectory.from).getOrElse(EsDirectory.default)
  }

  private val builder = OParser.builder[Config]

  import builder.*

  private lazy val parser = OParser.sequence(
    head("ROR tools", "1.0.0"),
    programName("java -jar ror-tools.jar"),
    patchCommand,
    note(""),
    opt[String]("I-understand-implications-of-ES-patching").optional()
      .valueName("<yes/no>")
      .validate {
        case "yes" => success
        case "no" => success
        case other => failure(s"ERROR: Invalid value [$other]. Only values 'yes' and 'no' can be provided as an answer.")
      }
      .action { (answer, config) =>
        answer match {
          case "yes" => config.copy(userUnderstandsImplicationsOfESPatching = UserUnderstandsImplicationsOfESPatching.Yes)
          case "no" => config.copy(userUnderstandsImplicationsOfESPatching = UserUnderstandsImplicationsOfESPatching.No)
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

  private final case class Config(command: Command,
                                  userUnderstandsImplicationsOfESPatching: UserUnderstandsImplicationsOfESPatching)

  private sealed trait Command
  private object Command {
    final case class Patch(customEsPath: Option[os.Path]) extends Command
    final case class Unpatch(customEsPath: Option[os.Path]) extends Command
    final case class Verify(customEsPath: Option[os.Path]) extends Command
  }

  private sealed trait UserUnderstandsImplicationsOfESPatching
  private object UserUnderstandsImplicationsOfESPatching {
    case object Yes extends UserUnderstandsImplicationsOfESPatching
    case object No extends UserUnderstandsImplicationsOfESPatching
    case object AnswerNotGiven extends UserUnderstandsImplicationsOfESPatching
  }

  sealed trait Result
  object Result {
    case object CommandNotParsed extends Result
    case object Success extends Result
    final case class Failure(exitCode: Int) extends Result
  }

}
