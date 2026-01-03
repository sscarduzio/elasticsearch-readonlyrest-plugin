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
package tech.beshu.ror.tools.core.patches.internal.modifiers.bytecodeJars.authorization

import org.objectweb.asm.*
import tech.beshu.ror.tools.core.patches.internal.modifiers.BytecodeJarModifier

import java.io.{File, InputStream}

/**
 * Patches RBACEngine#getUserPrivileges so that null AuthorizationInfo is replaced with
 * RorAuthorizationInfoProvider.get() (a prebuilt “superuser-like” AuthorizationInfo).
 *
 * Works for both ES 6.7.0+ (AuthorizationInfo is 2nd arg) and ES 8.3.0+ (AuthorizationInfo is 1st arg).
 */
private[patches] object ModifyRBACEngineClass extends BytecodeJarModifier {

  override def apply(jar: File): Unit = {
    modifyFileInJar(
      jar = jar,
      filePathString = "org/elasticsearch/xpack/security/authz/RBACEngine.class",
      processFileContent = doModifyRbacEngineClass
    )
  }

  private def doModifyRbacEngineClass(moduleInputStream: InputStream): Array[Byte] = {
    val reader = new ClassReader(moduleInputStream)

    // Recompute frames/maxs after inserting bytecode at method start.
    val writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
      override protected def getCommonSuperClass(type1: String, type2: String): String =
        "java/lang/Object" // conservative, avoids needing ES classes on patcher classpath
    }

    reader.accept(new EsClassVisitor(writer), ClassReader.EXPAND_FRAMES)
    writer.toByteArray
  }

  private class EsClassVisitor(writer: ClassWriter)
    extends ClassVisitor(Opcodes.ASM9, writer) {

    override def visitMethod(access: Int,
                             name: String,
                             descriptor: String,
                             signature: String,
                             exceptions: Array[String]): MethodVisitor = {

      val mv = super.visitMethod(access, name, descriptor, signature, exceptions)

      if (name != "getUserPrivileges") return mv

      val authInfoInternal =
        "org/elasticsearch/xpack/core/security/authz/AuthorizationEngine$AuthorizationInfo"

      val args = Type.getArgumentTypes(descriptor)

      // find AuthorizationInfo parameter index in the argument list
      val authArgPos = args.indexWhere(t => t.getSort == Type.OBJECT && t.getInternalName == authInfoInternal)
      if (authArgPos < 0) return mv

      // compute local variable slot for that argument (slot 0 = this)
      val authSlot = 1 + args.take(authArgPos).map(_.getSize).sum

      new InjectNullAuthInfoFix(mv, authSlot)
    }
  }

  private class InjectNullAuthInfoFix(underlying: MethodVisitor, authSlot: Int)
    extends MethodVisitor(Opcodes.ASM9, underlying) {

    override def visitCode(): Unit = {
      super.visitCode()

      // if (authorizationInfo == null) authorizationInfo = RorAuthorizationInfoProvider.get();
      val nonNull = new Label()

      mv.visitVarInsn(Opcodes.ALOAD, authSlot)
      mv.visitJumpInsn(Opcodes.IFNONNULL, nonNull)

      mv.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        "org/elasticsearch/xpack/security/authz/RorAuthorizationInfoProvider",
        "get",
        "()Lorg/elasticsearch/xpack/core/security/authz/AuthorizationEngine$AuthorizationInfo;",
        false
      )
      mv.visitVarInsn(Opcodes.ASTORE, authSlot)

      mv.visitLabel(nonNull)
      // original method body continues unchanged
    }
  }
}