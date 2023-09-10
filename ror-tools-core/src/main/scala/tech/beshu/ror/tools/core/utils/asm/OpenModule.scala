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
package tech.beshu.ror.tools.core.utils.asm

import org.objectweb.asm._

import java.io.{File, InputStream}

object OpenModule extends BytecodeJarModifier {

  override def apply(jar: File): Unit = {
    val updatedContentOfModuleInfo = loadAndProcessFileFromJar(
      jar = jar,
      classFileName = "module-info",
      processFileContent = doOpenModule
    )
    updateFileInJar(
      jar = jar,
      destinationPathSting = "/module-info.class",
      newContent = updatedContentOfModuleInfo
    )
  }

  private def doOpenModule(moduleInputStream: InputStream) = {
    val reader = new ClassReader(moduleInputStream)
    val writer = new ClassWriter(reader, 0)
    reader.accept(new EsClassVisitor(writer), 0)
    writer.toByteArray
  }

  private class EsClassVisitor(writer: ClassWriter)
    extends ClassVisitor(Opcodes.ASM9, writer) {

    override def visitModule(name: String, access: Int, version: String): ModuleVisitor = {
      new EsModuleVisitor(super.visitModule(name, access | Opcodes.ACC_OPEN, version))
    }
  }

  private class EsModuleVisitor(underlying: ModuleVisitor)
    extends ModuleVisitor(Opcodes.ASM9, underlying) {

    override def visitOpen(packaze: String, access: Int, modules: String*): Unit = {
      // skipping it to remove opening specific packages
    }
  }
}
