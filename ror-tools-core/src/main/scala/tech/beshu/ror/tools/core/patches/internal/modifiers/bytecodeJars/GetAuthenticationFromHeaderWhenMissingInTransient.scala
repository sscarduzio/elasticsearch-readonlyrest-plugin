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

private [patches] object GetAuthenticationFromHeaderWhenMissingInTransient extends BytecodeJarModifier {

  override def apply(jar: File): Unit = {
    modifyFileInJar(
      jar = jar,
      filePathString = "org/elasticsearch/xpack/core/security/authc/Authentication.class",
      processFileContent = doGetAuthenticationFromHeaderWhenMissingInTransient
    )
  }

  private def doGetAuthenticationFromHeaderWhenMissingInTransient(moduleInputStream: InputStream) = {
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
        case "getAuthentication" =>
          new GetAuthenticationMethodTakingIntoConsiderationContextHeader(super.visitMethod(access, name, descriptor, signature, exceptions))
        case _ =>
          super.visitMethod(access, name, descriptor, signature, exceptions)
      }
    }
  }

  private class GetAuthenticationMethodTakingIntoConsiderationContextHeader(underlying: MethodVisitor)
    extends MethodVisitor(Opcodes.ASM9) {

    override def visitCode(): Unit = {
      underlying.visitCode()
      val label0 = new Label()
      val label1 = new Label()
      val label2 = new Label()
      underlying.visitTryCatchBlock(label0, label1, label2, "java/io/IOException")
      val label3 = new Label()
      val label4 = new Label()
      underlying.visitTryCatchBlock(label3, label4, label2, "java/io/IOException")
      val label5 = new Label()
      underlying.visitLabel(label5)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitLdcInsn("_xpack_security_authentication")
      underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/common/util/concurrent/ThreadContext", "getTransient", "(Ljava/lang/String;)Ljava/lang/Object;", false)
      underlying.visitTypeInsn(Opcodes.CHECKCAST, "org/elasticsearch/xpack/core/security/authc/Authentication")
      underlying.visitVarInsn(Opcodes.ASTORE, 1)
      underlying.visitLabel(label0)
      underlying.visitVarInsn(Opcodes.ALOAD, 1)
      underlying.visitJumpInsn(Opcodes.IFNONNULL, label3)
      val label6 = new Label()
      underlying.visitLabel(label6)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitLdcInsn("_xpack_security_authentication")
      underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/common/util/concurrent/ThreadContext", "getHeader", "(Ljava/lang/String;)Ljava/lang/String;", false)
      underlying.visitVarInsn(Opcodes.ASTORE, 2)
      val label7 = new Label()
      underlying.visitLabel(label7)
      underlying.visitVarInsn(Opcodes.ALOAD, 2)
      val label8 = new Label()
      underlying.visitJumpInsn(Opcodes.IFNULL, label8)
      underlying.visitVarInsn(Opcodes.ALOAD, 2)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitMethodInsn(Opcodes.INVOKESTATIC, "org/elasticsearch/xpack/core/security/authc/Authentication", "deserializeHeaderAndPutInContext", "(Ljava/lang/String;Lorg/elasticsearch/common/util/concurrent/ThreadContext;)Lorg/elasticsearch/xpack/core/security/authc/Authentication;", false)
      underlying.visitJumpInsn(Opcodes.GOTO, label1)
      underlying.visitLabel(label8)
      underlying.visitFrame(Opcodes.F_APPEND, 2, Array("org/elasticsearch/xpack/core/security/authc/Authentication", "java/lang/String"), 0, null)
      underlying.visitInsn(Opcodes.ACONST_NULL)
      underlying.visitLabel(label1)
      underlying.visitFrame(Opcodes.F_SAME1, 0, null, 1, Array("org/elasticsearch/xpack/core/security/authc/Authentication"))
      underlying.visitInsn(Opcodes.ARETURN)
      underlying.visitLabel(label3)
      underlying.visitFrame(Opcodes.F_CHOP, 1, null, 0, null)
      underlying.visitVarInsn(Opcodes.ALOAD, 1)
      underlying.visitLabel(label4)
      underlying.visitInsn(Opcodes.ARETURN)
      underlying.visitLabel(label2)
      underlying.visitFrame(Opcodes.F_SAME1, 0, null, 1, Array("java/io/IOException"))
      underlying.visitVarInsn(Opcodes.ASTORE, 2)
      val label9 = new Label()
      underlying.visitLabel(label9)
      underlying.visitInsn(Opcodes.ACONST_NULL)
      underlying.visitInsn(Opcodes.ARETURN)
      val label10 = new Label()
      underlying.visitLabel(label10)
      underlying.visitLocalVariable("authenticationHeader", "Ljava/lang/String;", null, label7, label3, 2)
      underlying.visitLocalVariable("e", "Ljava/io/IOException;", null, label9, label10, 2)
      underlying.visitLocalVariable("context", "Lorg/elasticsearch/common/util/concurrent/ThreadContext;", null, label5, label10, 0)
      underlying.visitLocalVariable("auth", "Lorg/elasticsearch/xpack/core/security/authc/Authentication;", null, label0, label10, 1)
      underlying.visitMaxs(2, 3)
      underlying.visitEnd()
    }
  }
}
