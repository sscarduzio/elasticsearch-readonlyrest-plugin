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
import tech.beshu.ror.tools.core.utils.*
import tech.beshu.ror.tools.core.utils.InOut.ConsoleInOut

import scala.util.{Failure, Success, Try}

object RorToolsApp extends RorTools {

  def main(args: Array[String]): Unit = {
    run(args)(ConsoleInOut, OsRawEnvVariablesProvider) match {
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

  def run(args: Array[String])
         (implicit inOut: InOut,
          rawEnvVariablesProvider: RawEnvVariablesProvider): Result = {
    val allArgs = args ++ readArgsFromEnvVariables()
    OParser.runParser(
      parser,
      allArgs.map(arg => if (arg.startsWith("--")) arg.toLowerCase else arg),
      ScriptArguments(None),
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

  private def readArgsFromEnvVariables()
                                      (implicit rawEnvVariablesProvider: RawEnvVariablesProvider): Array[String] = {
    val allowedEnvVariableNames = List(
      consentFlagName,
    )
    rawEnvVariablesProvider.getSysEnv.toList
      .filter(env => allowedEnvVariableNames.contains(env._1.toLowerCase))
      .flatMap { case (name, value) => Array(s"--${name.toLowerCase}", value) }
      .toArray
  }

  private def handleParsedArguments(parsedArguments: ScriptArguments)
                                   (implicit inOut: InOut) = {
    Try {
      parsedArguments.command match {
        case Some(command: Command.Patch) =>
          new PatchCommandHandler().handle(command)
        case Some(command: Command.Unpatch) =>
          new UnpatchCommandHandler().handle(command)
        case Some(command: Command.Verify) =>
          new VerifyCommandHandler().handle(command)
        case None =>
          // This case is handled by OParser validation, the usage info is displayed when no command is provided
          Result.Failure
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

    def handle(command: Command.Patch): Result = {
      command.patchingConsent match {
        case Right(PatchingConsent.Accepted) =>
          performPatching(command.customEsPath)
        case Right(PatchingConsent.Rejected) =>
          abortPatchingBecauseUserDidNotAcceptConsequences()
        case Right(PatchingConsent.AnswerNotGiven) =>
          askUserAboutPatchingConsent() match {
            case PatchingConsent.Accepted => performPatching(command.customEsPath)
            case PatchingConsent.Rejected => abortPatchingBecauseUserDidNotAcceptConsequences()
            case PatchingConsent.AnswerNotGiven => Result.Failure
          }
        case Left(_) =>
          // This case is handled by OParser validation, the error message is shown
          Result.Failure
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

  private val builder = OParser.builder[ScriptArguments]

  import builder.*

  private lazy val parser = OParser.sequence(
    head("ROR tools", "1.0.0"),
    programName("java -jar ror-tools.jar"),
    patchCommand,
    note(""),
    unpatchCommand,
    note(""),
    verifyCommand,
    note(""),
    help('h', "help").text("prints this usage text"),
    checkConfig { c =>
      c.command match {
        case Some(_) => success
        case None =>  failure("No command provided. See usage below.")
      }
    },
  )

  private lazy val patchCommand =
    cmd("patch")
      .action((_, c) => c.copy(command = Some(Command.Patch(None, List.empty))))
      .text("patch is a command that modifies ES installation for ROR purposes")
      .children(
        esPathOption.action((p, args) => modifyPatchCommand(args, _.copy(customEsPath = Some(os.Path(p))))),
        patchingConsent,
        checkConfig { c =>
          c.command match {
            case Some(patch: Command.Patch) =>
              patch.patchingConsent match {
                case Right(_) => success
                case Left(error) => failure(error)
              }
            case Some(_) | None =>
              success
          }
        },
      )

  private lazy val unpatchCommand =
    cmd("unpatch")
      .action((_, c) => c.copy(command = Some(Command.Unpatch(None))))
      .text("unpatch is a command that reverts modifications done by patching")
      .children(
        esPathOption.action((p, args) => modifyCommand(args, _.asInstanceOf[Command.Unpatch].copy(customEsPath = Some(os.Path(p))))),
        patchingConsentDefinition.hidden(), // Accepts patching consent argument, for example passed as env variable, but does not require or use it
      )

  private lazy val verifyCommand =
    cmd("verify")
      .action((_, c) => c.copy(command = Some(Command.Verify(None))))
      .text("verify is a command that verifies if ES installation is patched")
      .children(
        esPathOption.action((p, args) => modifyCommand(args, _.asInstanceOf[Command.Verify].copy(customEsPath = Some(os.Path(p))))),
        patchingConsentDefinition.hidden(), // Accepts patching consent argument, for example passed as env variable, but does not require or use it
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

  private lazy val patchingConsent =
    patchingConsentDefinition
      .validate { value =>
        value.toLowerCase match {
          case "yes" => success
          case "no" => success
          case other => failure(s"ERROR: Invalid value [$other]. Only values 'yes' and 'no' can be provided as an answer.")
        }
      }
      .action { (answer, args) =>
        answer.toLowerCase match {
          case "yes" => addPatchingConsentValue(args, PatchingConsent.Accepted)
          case "no" => addPatchingConsentValue(args, PatchingConsent.Rejected)
        }
      }
      .text("Optional, when provided with value 'yes', it confirms that the user understands and accepts the implications of ES patching. The patching can therefore be performed. When not provided, user will be asked for confirmation in interactive mode. You can read about patching in our documentation: https://docs.readonlyrest.com/elasticsearch#id-3.-patch-elasticsearch.")

  private lazy val patchingConsentDefinition =
    opt[String](consentFlagName)
      .unbounded()
      .valueName("<yes/no>")

  private def addPatchingConsentValue(scriptArguments: ScriptArguments, patchingConsent: PatchingConsent) = {
    modifyPatchCommand(scriptArguments, patch => patch.copy(patchingConsentValues = patch.patchingConsentValues ::: patchingConsent :: Nil))
  }

  private def modifyPatchCommand(scriptArguments: ScriptArguments, f: Command.Patch => Command.Patch) = {
    modifyCommand(scriptArguments, command => f(command.asInstanceOf[Command.Patch]))
  }

  private def modifyCommand(scriptArguments: ScriptArguments, f: Command => Command) = {
    scriptArguments.copy(command = scriptArguments.command.map(c => f(c)))
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
  private val consentFlagName = "i_understand_and_accept_es_patching"

  private final case class ScriptArguments(command: Option[Command])

  private sealed trait Command
  private object Command {
    final case class Patch(customEsPath: Option[os.Path], patchingConsentValues: List[PatchingConsent]) extends Command {
      def patchingConsent: Either[String, PatchingConsent] = {
        if (patchingConsentValues.isEmpty) Right(PatchingConsent.AnswerNotGiven)
        else if (patchingConsentValues.forall(_ == PatchingConsent.Accepted)) Right(PatchingConsent.Accepted)
        else if (patchingConsentValues.forall(_ == PatchingConsent.Rejected)) Right(PatchingConsent.Rejected)
        else Left(s"There are conflicting values of the ${consentFlagName.toUpperCase} setting. Please check env variables and program arguments.")
      }
    }
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
