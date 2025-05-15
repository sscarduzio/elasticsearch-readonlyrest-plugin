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
import scopt.*
import tech.beshu.ror.tools.RorTools.*
import tech.beshu.ror.tools.core.patches.base.EsPatchExecutor
import tech.beshu.ror.tools.core.utils.InOut.ConsoleInOut
import tech.beshu.ror.tools.core.utils.RorToolsError.EsNotPatchedError
import tech.beshu.ror.tools.core.utils.{EsDirectory, InOut, RorToolsError, RorToolsException}

import scala.util.{Failure, Success, Try}

object RorToolsApp extends RorTools {

  def main(args: Array[String]): Unit = {
    run(args)(ConsoleInOut) match {
      case Result.Success =>
        ()
      case Result.Failure =>
        // The details of the failure should have been
        // already printed by the RorTools
        sys.exit(1)
      case Result.CommandNotParsed =>
        // The details of why the command was not parsed should have been
        // already printed by the scopt library parser
        sys.exit(1)
    }
  }

}

trait RorTools {

  def run(args: Array[String])(implicit inOut: InOut): Result = {
    OParser.runParser(
      parser,
      args.map(arg => if (arg.startsWith("--")) arg.toLowerCase else arg),
      Arguments(Command.Verify(None), PatchingConsent.AnswerNotGiven),
      parserSetup,
    ) match {
      case (result, effects) =>
        OParser.runEffects(effects, effectSetup)
        result match {
          case None =>
            Result.CommandNotParsed
          case Some(parsedArguments) =>
            handleParsedArguments(parsedArguments)
        }
    }
  }

  private def handleParsedArguments(parsedArguments: Arguments)
                                   (implicit inOut: InOut) = {
    Try {
      parsedArguments.command match {
        case command: Command.Patch =>
          new PatchCommandHandler().handle(command, parsedArguments)
        case command: Command.Unpatch =>
          new UnpatchCommandHandler().handle(command)
        case command: Command.Verify =>
          new VerifyCommandHandler().handle(command)
      }
    } match {
      case Failure(ex: RorToolsException) =>
        inOut.println(s"ERROR: ${ex.getMessage}\n${ex.printStackTrace()}")
        Result.Failure
      case Failure(ex: Throwable) =>
        inOut.println(s"UNEXPECTED ERROR: ${ex.getMessage}\n${ex.printStackTrace()}")
        Result.Failure
      case Success(result) =>
        result
    }
  }

  private class PatchCommandHandler(implicit inOut: InOut) {

    def handle(command: Command.Patch,
               arguments: Arguments): Result = {
      arguments.userUnderstandsImplicationsOfESPatching match {
        case PatchingConsent.Accepted =>
          performPatching(command.customEsPath)
        case PatchingConsent.Rejected =>
          abortPatchingBecauseUserDidNotAcceptConsequences()
        case PatchingConsent.AnswerNotGiven =>
          askUserAboutPatchingConsent() match {
            case PatchingConsent.Accepted => performPatching(command.customEsPath)
            case PatchingConsent.Rejected => abortPatchingBecauseUserDidNotAcceptConsequences()
            case PatchingConsent.AnswerNotGiven => Result.Failure
          }
      }
    }

    private def performPatching(customESPath: Option[Path]): Result = {
      val esDirectory = esDirectoryFrom(customESPath)
      EsPatchExecutor.create(esDirectory).patch() match {
        case Right(()) => Result.Success
        case Left(error) => failureCausedByRorToolsError(error)
      }
    }

    private def abortPatchingBecauseUserDidNotAcceptConsequences(): Result = {
      inOut.println("You have to confirm, that You understand the implications of ES patching in order to perform it.\nYou can read about patching in our documentation: https://docs.readonlyrest.com/elasticsearch#id-3.-patch-elasticsearch.")
      Result.Failure
    }

    private def askUserAboutPatchingConsent(): PatchingConsent = {
      inOut.println("Elasticsearch needs to be patched to work with ReadonlyREST. You can read about patching in our documentation: https://docs.readonlyrest.com/elasticsearch#id-3.-patch-elasticsearch.")
      inOut.print("Do you understand the implications of ES patching? (yes/no): ")
      inOut.readLine() match {
        case Some("") | None =>
          inOut.println("\nIt seems that the answer was not given or the ror-tools are executed in the environment that does not support console input.")
          inOut.println("Consider using silent mode and provide the answer using the parameter --I_UNDERSTAND_AND_ACCEPT_ES_PATCHING, read more in our documentation https://docs.readonlyrest.com/elasticsearch#id-5.-patch-elasticsearch.")
          PatchingConsent.AnswerNotGiven
        case Some(line) =>
          line.toLowerCase match {
            case "yes" => PatchingConsent.Accepted
            case _ => PatchingConsent.Rejected
          }
      }
    }

  }

  private class UnpatchCommandHandler(implicit inOut: InOut) {
    def handle(command: Command.Unpatch): Result = {
      val esDirectory = esDirectoryFrom(command.customEsPath)
      EsPatchExecutor.create(esDirectory).restore() match {
        case Right(()) => Result.Success
        case Left(error) => failureCausedByRorToolsError(error)
      }
    }
  }

  private class VerifyCommandHandler(implicit inOut: InOut) {
    def handle(command: Command.Verify): Result = {
      val esDirectory = esDirectoryFrom(command.customEsPath)
      EsPatchExecutor.create(esDirectory).verify() match {
        case Right(true) => Result.Success
        case Right(false) => Result.Failure
        case Left(error) => failureCausedByRorToolsError(error)
      }
    }
  }

  private def esDirectoryFrom(esPath: Option[os.Path]) = {
    esPath.map(EsDirectory.from).getOrElse(EsDirectory.default)
  }

  private def failureCausedByRorToolsError(error: RorToolsError)
                                          (implicit inOut: InOut) = {
    inOut.printlnErr(s"ERROR: ${error.message}")
    Result.Failure
  }

  private val builder = OParser.builder[Arguments]

  import builder.*

  private lazy val parser = OParser.sequence(
    head("ROR tools", "1.0.0"),
    programName("java -jar ror-tools.jar"),
    patchCommand,
    note(""),
    opt[String]("i_understand_and_accept_es_patching")
      .valueName("<yes/no>")
      .validate {
        case "yes" => success
        case "no" => success
        case other => failure(s"ERROR: Invalid value [$other]. Only values 'yes' and 'no' can be provided as an answer.")
      }
      .action { (answer, config) =>
        answer.toLowerCase match {
          case "yes" => config.copy(userUnderstandsImplicationsOfESPatching = PatchingConsent.Accepted)
          case "no" => config.copy(userUnderstandsImplicationsOfESPatching = PatchingConsent.Rejected)
        }
      }
      .text("Optional, when provided with value 'yes', it confirms that the user understands and accepts the implications of ES patching. The patching can therefore be performed. When not provided, user will be asked for confirmation in interactive mode. You can read about patching in our documentation: https://docs.readonlyrest.com/elasticsearch#id-3.-patch-elasticsearch."),
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
      .text(s"Path to elasticsearch directory; default=/usr/share/elasticsearch")
      .validate { path =>
        Try(os.Path(path))
          .toEither
          .flatMap { p => Either.cond(os.exists(p), (), ()) }
          .left.map(_ => s"Path [$path] does not exist")
      }

  private def parserSetup: OParserSetup = new DefaultOParserSetup {
    override def showUsageOnError: Option[Boolean] = Some(true)
  }

  private def effectSetup(implicit inOut: InOut): OEffectSetup = new DefaultOEffectSetup {
    override def displayToOut(msg: String): Unit = inOut.println(msg)

    override def displayToErr(msg: String): Unit = inOut.printlnErr(msg)
  }

}

object RorTools {
  private final case class Arguments(command: Command,
                                     userUnderstandsImplicationsOfESPatching: PatchingConsent)

  private sealed trait Command
  private object Command {
    final case class Patch(customEsPath: Option[os.Path]) extends Command
    final case class Unpatch(customEsPath: Option[os.Path]) extends Command
    final case class Verify(customEsPath: Option[os.Path]) extends Command
  }

  private sealed trait PatchingConsent
  private object PatchingConsent {
    case object Accepted extends PatchingConsent
    case object Rejected extends PatchingConsent
    case object AnswerNotGiven extends PatchingConsent
  }

  sealed trait Result
  object Result {
    case object CommandNotParsed extends Result
    case object Success extends Result
    case object Failure extends Result
  }
}
