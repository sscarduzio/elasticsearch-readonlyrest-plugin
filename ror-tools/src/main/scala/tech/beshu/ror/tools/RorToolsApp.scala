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

import scopt.OParser
import tech.beshu.ror.tools.core.actions._
import tech.beshu.ror.tools.core.patches.EsPatch
import tech.beshu.ror.tools.core.utils.{EsDirectory, RorToolsException}

import scala.util.Try

object RorToolsApp {

  def main(args: Array[String]): Unit = {
    OParser
      .parse(parser, args, Config(Command.Verify(None)))
      .foreach { config =>
        Try {
          config.command match {
            case Command.Patch(customEsPath) =>
              val esDirectory = esDirectoryFrom(customEsPath)
              new PatchAction(EsPatch.create(esDirectory)).execute()
            case Command.Unpatch(customEsPath) =>
              val esDirectory = esDirectoryFrom(customEsPath)
              new UnpatchAction(EsPatch.create(esDirectory)).execute()
            case Command.Verify(customEsPath) =>
              val esDirectory = esDirectoryFrom(customEsPath)
              new VerifyAction(EsPatch.create(esDirectory)).execute()
          }
        } recover {
          case ex: RorToolsException =>
            println(s"ERROR: ${ex.getMessage}")
            sys.exit(1)
        }
      }
  }

  private def esDirectoryFrom(esPath: Option[os.Path]) = {
    esPath.map(EsDirectory.from).getOrElse(EsDirectory.default)
  }

  private val builder = OParser.builder[Config]

  import builder._

  private lazy val parser = OParser.sequence(
    head("ROR tools", "1.0.0"),
    programName("java -jar ror-tools.jar"),
    patchCommand,
    note(""),
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

  private final case class Config(command: Command)

  private sealed trait Command
  private object Command {
    final case class Patch(customEsPath: Option[os.Path]) extends Command
    final case class Unpatch(customEsPath: Option[os.Path]) extends Command
    final case class Verify(customEsPath: Option[os.Path]) extends Command
  }

}
