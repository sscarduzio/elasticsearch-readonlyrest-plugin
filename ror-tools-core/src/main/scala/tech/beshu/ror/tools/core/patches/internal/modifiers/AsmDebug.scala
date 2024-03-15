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
package tech.beshu.ror.tools.core.patches.internal.modifiers

import org.objectweb.asm.ClassReader
import org.objectweb.asm.util.{ASMifier, TraceClassVisitor}

import java.io.{PrintWriter, StringWriter}
import scala.util.Using

private [modifiers] trait AsmDebug {
  protected def debug(bytecode: Array[Byte]): Unit = {
    println {
      s"""ASMified bytecode produced by ${this.getClass.getSimpleName}:
         |*********************************************************************
         |${asmifyBytecode(bytecode)}
         |*********************************************************************
         |""".stripMargin
    }
  }

  private def asmifyBytecode(bytecode: Array[Byte]): String = {
    val stringWriter = new StringWriter()
    Using(new PrintWriter(stringWriter)) { printWriter =>
      val classReader = new ClassReader(bytecode)
      val visitor = new TraceClassVisitor(null, new ASMifier(), printWriter)
      classReader.accept(visitor, 0)
    }
    stringWriter.toString
  }
}
