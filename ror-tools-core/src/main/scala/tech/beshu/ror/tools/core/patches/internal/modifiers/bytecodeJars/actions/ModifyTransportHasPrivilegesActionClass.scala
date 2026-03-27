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
package tech.beshu.ror.tools.core.patches.internal.modifiers.bytecodeJars.actions

import better.files.File
import org.objectweb.asm.*
import tech.beshu.ror.tools.core.patches.internal.modifiers.BytecodeJarModifier

import java.io.InputStream

/*
  It replaces the implementation of the isSameUser(...) method so that it always returns true,
  bypassing the identity check that normally restricts the "has privileges" transport action to only
  allow a user to query privileges for themselves. This ensures ROR can perform privilege checks on
  behalf of any user without being blocked by the same-user guard.
*/
private [patches] object ModifyTransportHasPrivilegesActionClass extends BytecodeJarModifier {

  override def apply(jar: File): Unit = {
    modifyFileInJar(
      jar = jar,
      filePathString = "org/elasticsearch/xpack/security/action/user/TransportHasPrivilegesAction.class",
      processFileContent = isSameUserAlwaysReturningTrue
    )
  }

  private def isSameUserAlwaysReturningTrue(moduleInputStream: InputStream) = {
    val reader = new ClassReader(moduleInputStream)
    val writer = new ClassWriter(reader, 0)
    reader.accept(new EsClassVisitor(writer), 0)
    writer.toByteArray
  }

  private class EsClassVisitor(writer: ClassWriter)
    extends ClassVisitor(Opcodes.ASM9, writer) {

    override def visitMethod(access: Int,
                             name: String,
                             descriptor: String,
                             signature: String,
                             exceptions: Array[String]): MethodVisitor = {
      name match {
        case "isSameUser" =>
          new IsSameUserMethodReturningTrue(super.visitMethod(access, name, descriptor, signature, exceptions))
        case _ =>
          super.visitMethod(access, name, descriptor, signature, exceptions)
      }
    }
  }

  private class IsSameUserMethodReturningTrue(underlying: MethodVisitor)
    extends MethodVisitor(Opcodes.ASM9) {
    override def visitCode(): Unit = {
      underlying.visitCode()

      // return true;
      underlying.visitInsn(Opcodes.ICONST_1)
      underlying.visitInsn(Opcodes.IRETURN)

      underlying.visitMaxs(1, 2)
      underlying.visitEnd()
    }
  }
}
