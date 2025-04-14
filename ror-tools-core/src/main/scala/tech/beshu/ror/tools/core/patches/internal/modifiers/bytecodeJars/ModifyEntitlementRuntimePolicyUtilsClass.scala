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

import org.objectweb.asm.*
import tech.beshu.ror.tools.core.patches.internal.modifiers.BytecodeJarModifier

import java.io.{File, InputStream}

private [patches] object ModifyEntitlementRuntimePolicyUtilsClass extends BytecodeJarModifier {

  override def apply(jar: File): Unit = {
    modifyFileInJar(
      jar = jar,
      filePathString = "org/elasticsearch/entitlement/runtime/policy/PolicyUtils.class",
      processFileContent = doTreatRorPluginAsInternalPlugin
    )
  }

  private def doTreatRorPluginAsInternalPlugin(moduleInputStream: InputStream) = {
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
        case "parsePolicyIfExists" =>
          new TreatRorPluginAsInternalPlugin(
            super.visitMethod(access, name, descriptor, signature, exceptions)
          )
        case _ =>
          super.visitMethod(access, name, descriptor, signature, exceptions)
      }
    }
  }


  private class TreatRorPluginAsInternalPlugin(underlying: MethodVisitor)
    extends MethodVisitor(Opcodes.ASM9) {

    override def visitCode(): Unit = {
      underlying.visitCode()
      val label0 = new Label()
      underlying.visitLabel(label0)
      underlying.visitLineNumber(142, label0)
      underlying.visitVarInsn(Opcodes.ALOAD, 1)
      underlying.visitInsn(Opcodes.ICONST_0)
      underlying.visitTypeInsn(Opcodes.ANEWARRAY, "java/nio/file/LinkOption")
      underlying.visitMethodInsn(Opcodes.INVOKESTATIC, "java/nio/file/Files", "exists", "(Ljava/nio/file/Path;[Ljava/nio/file/LinkOption;)Z", false)
      val label1 = new Label()
      underlying.visitJumpInsn(Opcodes.IFEQ, label1)
      val label2 = new Label()
      underlying.visitLabel(label2)
      underlying.visitLineNumber(143, label2)
      underlying.visitLdcInsn("readonlyrest")
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false)
      val label3 = new Label()
      underlying.visitJumpInsn(Opcodes.IFEQ, label3)
      underlying.visitInsn(Opcodes.ICONST_0)
      val label4 = new Label()
      underlying.visitJumpInsn(Opcodes.GOTO, label4)
      underlying.visitLabel(label3)
      underlying.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
      underlying.visitVarInsn(Opcodes.ILOAD, 2)
      underlying.visitLabel(label4)
      underlying.visitFrame(Opcodes.F_SAME1, 0, null, 1, Array(Opcodes.INTEGER))
      underlying.visitVarInsn(Opcodes.ISTORE, 3)
      val label5 = new Label()
      underlying.visitLabel(label5)
      underlying.visitLineNumber(144, label5)
      underlying.visitTypeInsn(Opcodes.NEW, "org/elasticsearch/entitlement/runtime/policy/PolicyParser")
      underlying.visitInsn(Opcodes.DUP)
      underlying.visitVarInsn(Opcodes.ALOAD, 1)
      underlying.visitInsn(Opcodes.ICONST_1)
      underlying.visitTypeInsn(Opcodes.ANEWARRAY, "java/nio/file/OpenOption")
      underlying.visitInsn(Opcodes.DUP)
      underlying.visitInsn(Opcodes.ICONST_0)
      underlying.visitFieldInsn(Opcodes.GETSTATIC, "java/nio/file/StandardOpenOption", "READ", "Ljava/nio/file/StandardOpenOption;")
      underlying.visitInsn(Opcodes.AASTORE)
      underlying.visitMethodInsn(Opcodes.INVOKESTATIC, "java/nio/file/Files", "newInputStream", "(Ljava/nio/file/Path;[Ljava/nio/file/OpenOption;)Ljava/io/InputStream;", false)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitVarInsn(Opcodes.ILOAD, 3)
      underlying.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/elasticsearch/entitlement/runtime/policy/PolicyParser", "<init>", "(Ljava/io/InputStream;Ljava/lang/String;Z)V", false)
      underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/entitlement/runtime/policy/PolicyParser", "parsePolicy", "()Lorg/elasticsearch/entitlement/runtime/policy/Policy;", false)
      underlying.visitInsn(Opcodes.ARETURN)
      underlying.visitLabel(label1)
      underlying.visitLineNumber(146, label1)
      underlying.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
      underlying.visitTypeInsn(Opcodes.NEW, "org/elasticsearch/entitlement/runtime/policy/Policy")
      underlying.visitInsn(Opcodes.DUP)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/List", "of", "()Ljava/util/List;", true)
      underlying.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/elasticsearch/entitlement/runtime/policy/Policy", "<init>", "(Ljava/lang/String;Ljava/util/List;)V", false)
      underlying.visitInsn(Opcodes.ARETURN)
      val label6 = new Label()
      underlying.visitLabel(label6)
      underlying.visitLocalVariable("modIsExternalPlugin", "Z", null, label5, label1, 3)
      underlying.visitLocalVariable("pluginName", "Ljava/lang/String;", null, label0, label6, 0)
      underlying.visitLocalVariable("policyFile", "Ljava/nio/file/Path;", null, label0, label6, 1)
      underlying.visitLocalVariable("isExternalPlugin", "Z", null, label0, label6, 2)
      underlying.visitMaxs(7, 4)
      underlying.visitEnd()
    }
  }

}
