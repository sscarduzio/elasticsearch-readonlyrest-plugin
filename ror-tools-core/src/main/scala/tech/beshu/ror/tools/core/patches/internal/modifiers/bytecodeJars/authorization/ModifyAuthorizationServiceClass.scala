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

import better.files.File
import just.semver.SemVer
import org.objectweb.asm.*
import tech.beshu.ror.tools.core.patches.internal.modifiers.BytecodeJarModifier
import tech.beshu.ror.tools.core.utils.EsUtil.{es670, es7160}

import java.io.InputStream

/*
  Elasticsearch’s AuthorizationService blocks requests without a full authorization flow. ROR needs to bypass this
  in controlled scenarios. This bytecode modifier rewrites authorize(…) to stash a superuser-like AuthorizationInfo
  in ThreadContext and immediately succeed, effectively short-circuiting X-Pack authorization.
*/
private[patches] class ModifyAuthorizationServiceClass private(esVersion: SemVer)
  extends BytecodeJarModifier {

  override def apply(jar: File): Unit = {
    modifyFileInJar(
      jar = jar,
      filePathString = "org/elasticsearch/xpack/security/authz/AuthorizationService.class",
      processFileContent = doMakeAuthorizeMethodDummy
    )
  }

  private def doMakeAuthorizeMethodDummy(moduleInputStream: InputStream) = {
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
        case "authorize" =>
          esVersion match {
            case v if v >= es7160 =>
              new DummyAuthorizationMethodForEsGreaterOrEqual7160(super.visitMethod(access, name, descriptor, signature, exceptions))
            case v if v >= es670 =>
              new DummyAuthorizationMethodForEsGreaterOrEqual670(super.visitMethod(access, name, descriptor, signature, exceptions))
            case _ =>
              super.visitMethod(access, name, descriptor, signature, exceptions)
          }
        case _ =>
          super.visitMethod(access, name, descriptor, signature, exceptions)
      }
    }
  }

  private class DummyAuthorizationMethodForEsGreaterOrEqual7160(underlying: MethodVisitor)
    extends MethodVisitor(Opcodes.ASM9) {

    override def visitCode(): Unit = {
      // -- begin: ensure _authz_info present in ThreadContext --
      val label0 = new Label()
      underlying.visitLabel(label0)

      // if (threadContext.getTransient("_authz_info") == null)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitFieldInsn(
        Opcodes.GETFIELD,
        "org/elasticsearch/xpack/security/authz/AuthorizationService",
        "threadContext",
        "Lorg/elasticsearch/common/util/concurrent/ThreadContext;"
      )
      underlying.visitLdcInsn("_authz_info")
      underlying.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "org/elasticsearch/common/util/concurrent/ThreadContext",
        "getTransient",
        "(Ljava/lang/String;)Ljava/lang/Object;",
        /* itf = */ false
      )
      val label1 = new Label()
      underlying.visitJumpInsn(Opcodes.IFNONNULL, label1)

      // threadContext.putTransient("_authz_info", RorAuthorizationInfoProvider.get())
      val label2 = new Label()
      underlying.visitLabel(label2)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitFieldInsn(
        Opcodes.GETFIELD,
        "org/elasticsearch/xpack/security/authz/AuthorizationService",
        "threadContext",
        "Lorg/elasticsearch/common/util/concurrent/ThreadContext;"
      )
      underlying.visitLdcInsn("_authz_info")
      underlying.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        "org/elasticsearch/xpack/security/authz/RorAuthorizationInfoProvider",
        "get",
        "()Lorg/elasticsearch/xpack/core/security/authz/AuthorizationEngine$AuthorizationInfo;",
        /* itf = */ false
      )
      val label4 = new Label()
      underlying.visitLabel(label4)
      underlying.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "org/elasticsearch/common/util/concurrent/ThreadContext",
        "putTransient",
        "(Ljava/lang/String;Ljava/lang/Object;)V",
        /* itf = */ false
      )
      // -- end: ensure _authz_info --
      // -- begin: ensure _indices_permissions present (allow all) --
      underlying.visitLabel(label1)
      underlying.visitFrame(Opcodes.F_SAME, 0, null, 0, null)

      // if (threadContext.getTransient("_indices_permissions") == null)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitFieldInsn(
        Opcodes.GETFIELD,
        "org/elasticsearch/xpack/security/authz/AuthorizationService",
        "threadContext",
        "Lorg/elasticsearch/common/util/concurrent/ThreadContext;"
      )
      underlying.visitLdcInsn("_indices_permissions")
      underlying.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "org/elasticsearch/common/util/concurrent/ThreadContext",
        "getTransient",
        "(Ljava/lang/String;)Ljava/lang/Object;",
        /* itf = */ false
      )
      val label5 = new Label()
      underlying.visitJumpInsn(Opcodes.IFNONNULL, label5)

      // threadContext.putTransient("_indices_permissions", IndicesAccessControl.allowAll())
      val label6 = new Label()
      underlying.visitLabel(label6)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitFieldInsn(
        Opcodes.GETFIELD,
        "org/elasticsearch/xpack/security/authz/AuthorizationService",
        "threadContext",
        "Lorg/elasticsearch/common/util/concurrent/ThreadContext;"
      )
      underlying.visitLdcInsn("_indices_permissions")
      val label7 = new Label()
      underlying.visitLabel(label7)
      underlying.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        "org/elasticsearch/xpack/core/security/authz/accesscontrol/IndicesAccessControl",
        "allowAll",
        "()Lorg/elasticsearch/xpack/core/security/authz/accesscontrol/IndicesAccessControl;",
        /* itf = */ false
      )
      val label8 = new Label()
      underlying.visitLabel(label8)
      underlying.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "org/elasticsearch/common/util/concurrent/ThreadContext",
        "putTransient",
        "(Ljava/lang/String;Ljava/lang/Object;)V",
        /* itf = */ false
      )
      // -- end: ensure _indices_permissions --
      // listener.onResponse(null)
      underlying.visitLabel(label5)
      underlying.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
      underlying.visitVarInsn(Opcodes.ALOAD, 4)
      underlying.visitInsn(Opcodes.ACONST_NULL)
      underlying.visitMethodInsn(
        Opcodes.INVOKEINTERFACE,
        "org/elasticsearch/action/ActionListener",
        "onResponse",
        "(Ljava/lang/Object;)V",
        /* itf = */ true
      )

      // return;
      val label9 = new Label()
      underlying.visitLabel(label9)
      underlying.visitInsn(Opcodes.RETURN)

      // locals/signature bookkeeping
      val label10 = new Label()
      underlying.visitLabel(label10)
      underlying.visitLocalVariable(
        "this",
        "Lorg/elasticsearch/xpack/security/authz/AuthorizationService;",
        null,
        label0,
        label10,
        0
      )
      underlying.visitLocalVariable(
        "authentication",
        "Lorg/elasticsearch/xpack/core/security/authc/Authentication;",
        null,
        label0,
        label10,
        1
      )
      underlying.visitLocalVariable(
        "action",
        "Ljava/lang/String;",
        null,
        label0,
        label10,
        2
      )
      underlying.visitLocalVariable(
        "originalRequest",
        "Lorg/elasticsearch/transport/TransportRequest;",
        null,
        label0,
        label10,
        3
      )
      underlying.visitLocalVariable(
        "listener",
        "Lorg/elasticsearch/action/ActionListener;",
        "Lorg/elasticsearch/action/ActionListener<Ljava/lang/Void;>;",
        label0,
        label10,
        4
      )
      underlying.visitMaxs(3, 5)
      underlying.visitEnd()
    }
  }

  private class DummyAuthorizationMethodForEsGreaterOrEqual670(underlying: MethodVisitor)
    extends MethodVisitor(Opcodes.ASM9) {

    override def visitCode(): Unit = {
      underlying.visitCode()

      val label0 = new Label()
      underlying.visitLabel(label0)

      // if (threadContext.getTransient("_authz_info") == null)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitFieldInsn(
        Opcodes.GETFIELD,
        "org/elasticsearch/xpack/security/authz/AuthorizationService",
        "threadContext",
        "Lorg/elasticsearch/common/util/concurrent/ThreadContext;"
      )
      underlying.visitLdcInsn("_authz_info")
      underlying.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "org/elasticsearch/common/util/concurrent/ThreadContext",
        "getTransient",
        "(Ljava/lang/String;)Ljava/lang/Object;",
        false
      )
      val label1 = new Label()
      underlying.visitJumpInsn(Opcodes.IFNONNULL, label1)

      // threadContext.putTransient("_authz_info", RorAuthorizationInfoProvider.get())
      val label2 = new Label()
      underlying.visitLabel(label2)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitFieldInsn(
        Opcodes.GETFIELD,
        "org/elasticsearch/xpack/security/authz/AuthorizationService",
        "threadContext",
        "Lorg/elasticsearch/common/util/concurrent/ThreadContext;"
      )
      underlying.visitLdcInsn("_authz_info")
      underlying.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        "org/elasticsearch/xpack/security/authz/RorAuthorizationInfoProvider",
        "get",
        "()Lorg/elasticsearch/xpack/core/security/authz/AuthorizationEngine$AuthorizationInfo;",
        false
      )
      underlying.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "org/elasticsearch/common/util/concurrent/ThreadContext",
        "putTransient",
        "(Ljava/lang/String;Ljava/lang/Object;)V",
        false
      )

      // if (threadContext.getTransient("_indices_permissions") == null)
      underlying.visitLabel(label1)
      underlying.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitFieldInsn(
        Opcodes.GETFIELD,
        "org/elasticsearch/xpack/security/authz/AuthorizationService",
        "threadContext",
        "Lorg/elasticsearch/common/util/concurrent/ThreadContext;"
      )
      underlying.visitLdcInsn("_indices_permissions")
      underlying.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "org/elasticsearch/common/util/concurrent/ThreadContext",
        "getTransient",
        "(Ljava/lang/String;)Ljava/lang/Object;",
        false
      )
      val label5 = new Label()
      underlying.visitJumpInsn(Opcodes.IFNONNULL, label5)

      // threadContext.putTransient("_indices_permissions", IndicesAccessControl.ALLOW_ALL)
      val label6 = new Label()
      underlying.visitLabel(label6)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitFieldInsn(
        Opcodes.GETFIELD,
        "org/elasticsearch/xpack/security/authz/AuthorizationService",
        "threadContext",
        "Lorg/elasticsearch/common/util/concurrent/ThreadContext;"
      )
      underlying.visitLdcInsn("_indices_permissions")
      underlying.visitFieldInsn(
        Opcodes.GETSTATIC,
        "org/elasticsearch/xpack/core/security/authz/accesscontrol/IndicesAccessControl",
        "ALLOW_ALL",
        "Lorg/elasticsearch/xpack/core/security/authz/accesscontrol/IndicesAccessControl;"
      )
      underlying.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "org/elasticsearch/common/util/concurrent/ThreadContext",
        "putTransient",
        "(Ljava/lang/String;Ljava/lang/Object;)V",
        false
      )

      // listener.onResponse(null)
      underlying.visitLabel(label5)
      underlying.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
      underlying.visitVarInsn(Opcodes.ALOAD, 4)
      underlying.visitInsn(Opcodes.ACONST_NULL)
      underlying.visitMethodInsn(
        Opcodes.INVOKEINTERFACE,
        "org/elasticsearch/action/ActionListener",
        "onResponse",
        "(Ljava/lang/Object;)V",
        true
      )

      // return;
      val label7 = new Label()
      underlying.visitLabel(label7)
      underlying.visitInsn(Opcodes.RETURN)

      // locals / stack
      val label8 = new Label()
      underlying.visitLabel(label8)
      underlying.visitLocalVariable(
        "this",
        "Lorg/elasticsearch/xpack/security/authz/AuthorizationService;",
        null,
        label0,
        label8,
        0
      )
      underlying.visitLocalVariable(
        "authentication",
        "Lorg/elasticsearch/xpack/core/security/authc/Authentication;",
        null,
        label0,
        label8,
        1
      )
      underlying.visitLocalVariable("action", "Ljava/lang/String;", null, label0, label8, 2)
      underlying.visitLocalVariable(
        "originalRequest",
        "Lorg/elasticsearch/transport/TransportRequest;",
        null,
        label0,
        label8,
        3
      )
      underlying.visitLocalVariable(
        "listener",
        "Lorg/elasticsearch/action/ActionListener;",
        "Lorg/elasticsearch/action/ActionListener<Ljava/lang/Void;>;",
        label0,
        label8,
        4
      )
      underlying.visitMaxs(3, 5)
      underlying.visitEnd()
    }
  }
}

object ModifyAuthorizationServiceClass {
  def apply(esVersion: SemVer): ModifyAuthorizationServiceClass = new ModifyAuthorizationServiceClass(esVersion)
}