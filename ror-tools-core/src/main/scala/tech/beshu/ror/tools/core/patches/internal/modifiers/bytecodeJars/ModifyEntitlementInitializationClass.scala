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

import just.semver.SemVer
import org.objectweb.asm.*
import tech.beshu.ror.tools.core.patches.internal.modifiers.BytecodeJarModifier
import tech.beshu.ror.tools.core.utils.EsUtil.{es8181, es901}

import java.io.{File, InputStream}

/**
 * Modifies the EntitlementInitialization class to bypass forbidden file path validation
 * specifically for the ReadonlyREST plugin. This is necessary because ReadonlyREST
 * requires access to certain paths that would otherwise be blocked by Elasticsearch's
 * security entitlements system in versions 8.18.1+ and 9.0.1+.
 */
private[patches] class ModifyEntitlementInitializationClass(esVersion: SemVer)
  extends BytecodeJarModifier {

  override def apply(jar: File): Unit = {
    modifyFileInJar(
      jar = jar,
      filePathString = "org/elasticsearch/entitlement/initialization/EntitlementInitialization.class",
      processFileContent = dontValidateForbiddenPathsInCaseOfRorPlugin
    )
  }

  private def dontValidateForbiddenPathsInCaseOfRorPlugin(moduleInputStream: InputStream) = {
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
        case "validateReadFilesEntitlements" =>
          esVersion match
            case v if v >= es901 =>
              new DontValidateForbiddenPathsInCaseOfRorPlugin(
                super.visitMethod(access, name, descriptor, signature, exceptions)
              )
            case v if v >= es8181 =>
              new DontValidateForbiddenPathsInCaseOfRorPlugin(
                super.visitMethod(access, name, descriptor, signature, exceptions)
              )
            case v =>
              super.visitMethod(access, name, descriptor, signature, exceptions)
        case _ =>
          super.visitMethod(access, name, descriptor, signature, exceptions)
      }
    }
  }

  /**
   * Modifies the bytecode of the validateReadFilesEntitlements method to add a check at the beginning.
   * If componentName equals "readonlyrest", it returns immediately, bypassing the validation logic.
   * Otherwise, it proceeds with the original validation logic.
   */
  private class DontValidateForbiddenPathsInCaseOfRorPlugin(underlying: MethodVisitor)
    extends MethodVisitor(Opcodes.ASM9) {

    override def visitCode(): Unit = {
      underlying.visitCode()
      val label0 = new Label()
      underlying.visitLabel(label0)
      underlying.visitLineNumber(407, label0)
      underlying.visitLdcInsn("readonlyrest")
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false)
      val label1 = new Label()
      underlying.visitJumpInsn(Opcodes.IFNE, label1)
      val label2 = new Label()
      underlying.visitLabel(label2)
      underlying.visitLineNumber(408, label2)
      underlying.visitVarInsn(Opcodes.ALOAD, 3)
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Set", "iterator", "()Ljava/util/Iterator;", true)
      underlying.visitVarInsn(Opcodes.ASTORE, 4)
      val label3 = new Label()
      underlying.visitLabel(label3)
      underlying.visitFrame(Opcodes.F_APPEND, 1, Array("java/util/Iterator"), 0, null)
      underlying.visitVarInsn(Opcodes.ALOAD, 4)
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true)
      underlying.visitJumpInsn(Opcodes.IFEQ, label1)
      underlying.visitVarInsn(Opcodes.ALOAD, 4)
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true)
      underlying.visitTypeInsn(Opcodes.CHECKCAST, "java/nio/file/Path")
      underlying.visitVarInsn(Opcodes.ASTORE, 5)
      val label4 = new Label()
      underlying.visitLabel(label4)
      underlying.visitLineNumber(409, label4)
      underlying.visitVarInsn(Opcodes.ALOAD, 2)
      underlying.visitVarInsn(Opcodes.ALOAD, 5)
      underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/entitlement/runtime/policy/FileAccessTree", "canRead", "(Ljava/nio/file/Path;)Z", false)
      val label5 = new Label()
      underlying.visitJumpInsn(Opcodes.IFEQ, label5)
      val label6 = new Label()
      underlying.visitLabel(label6)
      underlying.visitLineNumber(410, label6)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitVarInsn(Opcodes.ALOAD, 1)
      underlying.visitVarInsn(Opcodes.ALOAD, 5)
      underlying.visitFieldInsn(Opcodes.GETSTATIC, "org/elasticsearch/entitlement/runtime/policy/entitlements/FilesEntitlement$Mode", "READ", "Lorg/elasticsearch/entitlement/runtime/policy/entitlements/FilesEntitlement$Mode;")
      underlying.visitMethodInsn(Opcodes.INVOKESTATIC, "org/elasticsearch/entitlement/initialization/EntitlementInitialization", "buildValidationException", "(Ljava/lang/String;Ljava/lang/String;Ljava/nio/file/Path;Lorg/elasticsearch/entitlement/runtime/policy/entitlements/FilesEntitlement$Mode;)Ljava/lang/IllegalArgumentException;", false)
      underlying.visitInsn(Opcodes.ATHROW)
      underlying.visitLabel(label5)
      underlying.visitLineNumber(412, label5)
      underlying.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
      underlying.visitJumpInsn(Opcodes.GOTO, label3)
      underlying.visitLabel(label1)
      underlying.visitLineNumber(414, label1)
      underlying.visitFrame(Opcodes.F_CHOP, 1, null, 0, null)
      underlying.visitInsn(Opcodes.RETURN)
      val label7 = new Label()
      underlying.visitLabel(label7)
      underlying.visitLocalVariable("forbiddenPath", "Ljava/nio/file/Path;", null, label4, label5, 5)
      underlying.visitLocalVariable("componentName", "Ljava/lang/String;", null, label0, label7, 0)
      underlying.visitLocalVariable("moduleName", "Ljava/lang/String;", null, label0, label7, 1)
      underlying.visitLocalVariable("fileAccessTree", "Lorg/elasticsearch/entitlement/runtime/policy/FileAccessTree;", null, label0, label7, 2)
      underlying.visitLocalVariable("readForbiddenPaths", "Ljava/util/Set;", "Ljava/util/Set<Ljava/nio/file/Path;>;", label0, label7, 3)
      underlying.visitMaxs(4, 6)
      underlying.visitEnd()
    }
  }

}

