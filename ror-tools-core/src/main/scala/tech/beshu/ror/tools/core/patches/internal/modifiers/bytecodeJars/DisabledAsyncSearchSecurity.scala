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
package tech.beshu.ror.tools.core.patches.internal.modifiers.bytecodeJars

import org.objectweb.asm._
import tech.beshu.ror.tools.core.patches.internal.modifiers.BytecodeJarModifier

import java.io.{File, InputStream}

private [patches] object DisabledAsyncSearchSecurity extends BytecodeJarModifier {

  override def apply(jar: File): Unit = {
    modifyFileInJar(
      jar = jar,
      filePathString = "org/elasticsearch/xpack/core/async/AsyncSearchSecurity.class",
      processFileContent = doDisableAsyncSearchSecurity
    )
  }

  private def doDisableAsyncSearchSecurity(moduleInputStream: InputStream) = {
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
        case "hasClusterPrivilege" =>
          new HasClusterPrivilegeAlwaysReturnsTrue(super.visitMethod(access, name, descriptor, signature, exceptions))
        case _ =>
          super.visitMethod(access, name, descriptor, signature, exceptions)
      }
    }
  }

  private class HasClusterPrivilegeAlwaysReturnsTrue(underlying: MethodVisitor)
    extends MethodVisitor(Opcodes.ASM9) {

    override def visitCode(): Unit = {
      underlying.visitCode()
      val label0 = new Label()
      underlying.visitLabel(label0)
      underlying.visitVarInsn(Opcodes.ALOAD, 2)
      underlying.visitInsn(Opcodes.ICONST_1)
      underlying.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false)
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/elasticsearch/action/ActionListener", "onResponse", "(Ljava/lang/Object;)V", true)
      val label1 = new Label()
      underlying.visitLabel(label1)
      underlying.visitInsn(Opcodes.RETURN)
      val label2 = new Label()
      underlying.visitLabel(label2)
      underlying.visitLocalVariable("this", "Lorg/elasticsearch/xpack/core/async/AsyncSearchSecurity;", null, label0, label2, 0)
      underlying.visitLocalVariable("privilegeName", "Ljava/lang/String;", null, label0, label2, 1)
      underlying.visitLocalVariable("listener", "Lorg/elasticsearch/action/ActionListener;", "Lorg/elasticsearch/action/ActionListener<Ljava/lang/Boolean;>;", label0, label2, 2)
      underlying.visitMaxs(2, 3)
      underlying.visitEnd()
    }
  }
}
