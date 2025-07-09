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

import cats.data.NonEmptyList
import org.objectweb.asm.*
import tech.beshu.ror.tools.core.patches.internal.modifiers.BytecodeJarModifier

import java.io.{File, InputStream}
import java.security.Permission

private [patches] class ModifyBootstrapPolicyUtilClass(additionalAllowedPermissions: NonEmptyList[Permission])
  extends BytecodeJarModifier {

  override def apply(jar: File): Unit = {
    modifyFileInJar(
      jar = jar,
      filePathString = "org/elasticsearch/bootstrap/PolicyUtil.class",
      processFileContent = doGrantExclusivePermissionForRorPlugin
    )
  }

  private def doGrantExclusivePermissionForRorPlugin(moduleInputStream: InputStream) = {
    val reader = new ClassReader(moduleInputStream)
    val writer = new ClassWriter(reader, 0)
    reader.accept(new EsClassVisitor(writer), 0)
    writer.toByteArray
  }

  private class EsClassVisitor(writer: ClassWriter)
    extends ClassVisitor(Opcodes.ASM9, writer) {

    new CreateIsItRorPluginMethod(this)
    new CreateAllowedPluginPermissionsExclusivelyForRorMethod(this)

    override def visitMethod(access: Int,
                             name: String,
                             descriptor: String,
                             signature: String,
                             exceptions: Array[String]): MethodVisitor = {
      name match {
        case "getPluginPolicyInfo" =>
          new GetPluginPolicyInfoAddingRorExtraPermission(
            super.visitMethod(access, name, descriptor, signature, exceptions)
          )
        case _ =>
          super.visitMethod(access, name, descriptor, signature, exceptions)
      }
    }
  }

  private class CreateIsItRorPluginMethod(classVisitor: ClassVisitor) {
    private val methodVisitor = classVisitor.visitMethod(
      Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
      "isItRorPlugin",
      "(Lorg/elasticsearch/bootstrap/PluginPolicyInfo;)Z",
      null,
      null
    )
    methodVisitor.visitCode()
    methodVisitor.visitVarInsn(Opcodes.ALOAD, 0)
    methodVisitor.visitMethodInsn(
      Opcodes.INVOKEVIRTUAL,
      "org/elasticsearch/bootstrap/PluginPolicyInfo",
      "file",
      "()Ljava/nio/file/Path;",
      false
    )
    methodVisitor.visitMethodInsn(
      Opcodes.INVOKEINTERFACE,
      "java/nio/file/Path",
      "toAbsolutePath",
      "()Ljava/nio/file/Path;",
      true
    )
    methodVisitor.visitMethodInsn(
      Opcodes.INVOKEINTERFACE,
      "java/nio/file/Path",
      "toString",
      "()Ljava/lang/String;",
      true
    )
    methodVisitor.visitLdcInsn("/readonlyrest/plugin-security.policy")
    methodVisitor.visitMethodInsn(
      Opcodes.INVOKEVIRTUAL,
      "java/lang/String",
      "endsWith",
      "(Ljava/lang/String;)Z",
      false
    )
    methodVisitor.visitInsn(Opcodes.IRETURN)
    methodVisitor.visitMaxs(2, 1)
    methodVisitor.visitEnd()
  }

  private class CreateAllowedPluginPermissionsExclusivelyForRorMethod(classVisitor: ClassVisitor) {
    private val methodVisitor = classVisitor.visitMethod(
      Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
      "allowedPluginPermissionsExclusivelyForRor",
      "()Lorg/elasticsearch/bootstrap/PolicyUtil$PermissionMatcher;",
      null,
      null
    )
    methodVisitor.visitCode()
    methodVisitor.visitTypeInsn(Opcodes.NEW, "java/security/Permissions")
    methodVisitor.visitInsn(Opcodes.DUP)
    methodVisitor.visitMethodInsn(
      Opcodes.INVOKESPECIAL,
      "java/security/Permissions",
      "<init>",
      "()V",
      false
    )
    methodVisitor.visitVarInsn(Opcodes.ASTORE, 0)
    methodVisitor.visitFieldInsn(
      Opcodes.GETSTATIC,
      "org/elasticsearch/bootstrap/PolicyUtil",
      "ALLOWED_PLUGIN_PERMISSIONS",
      "Lorg/elasticsearch/bootstrap/PolicyUtil$PermissionMatcher;"
    )
    methodVisitor.visitFieldInsn(
      Opcodes.GETFIELD,
      "org/elasticsearch/bootstrap/PolicyUtil$PermissionMatcher",
      "namedPermissions",
      "Ljava/security/PermissionCollection;"
    )
    methodVisitor.visitMethodInsn(
      Opcodes.INVOKEVIRTUAL,
      "java/security/PermissionCollection",
      "elementsAsStream",
      "()Ljava/util/stream/Stream;",
      false
    )
    methodVisitor.visitVarInsn(Opcodes.ALOAD, 0)
    methodVisitor.visitInsn(Opcodes.DUP)
    methodVisitor.visitMethodInsn(
      Opcodes.INVOKESTATIC,
      "java/util/Objects",
      "requireNonNull",
      "(Ljava/lang/Object;)Ljava/lang/Object;",
      false
    )
    methodVisitor.visitInsn(Opcodes.POP)
    methodVisitor.visitInvokeDynamicInsn(
      "accept",
      "(Ljava/security/PermissionCollection;)Ljava/util/function/Consumer;",
      new Handle(
        Opcodes.H_INVOKESTATIC,
        "java/lang/invoke/LambdaMetafactory",
        "metafactory",
        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
        false
      ),
      Type.getType("(Ljava/lang/Object;)V"),
      new Handle(
        Opcodes.H_INVOKEVIRTUAL,
        "java/security/PermissionCollection",
        "add",
        "(Ljava/security/Permission;)V",
        false
      ),
      Type.getType("(Ljava/security/Permission;)V")
    )
    methodVisitor.visitMethodInsn(
      Opcodes.INVOKEINTERFACE,
      "java/util/stream/Stream",
      "forEach",
      "(Ljava/util/function/Consumer;)V",
      true
    )
    additionalAllowedPermissions.toList.foreach { permission =>
      includeAdditionalPermission(methodVisitor, permission)
    }
    methodVisitor.visitVarInsn(Opcodes.ALOAD, 0)
    methodVisitor.visitMethodInsn(
      Opcodes.INVOKEVIRTUAL,
      "java/security/PermissionCollection",
      "setReadOnly",
      "()V",
      false
    )
    methodVisitor.visitTypeInsn(Opcodes.NEW, "org/elasticsearch/bootstrap/PolicyUtil$PermissionMatcher")
    methodVisitor.visitInsn(Opcodes.DUP)
    methodVisitor.visitVarInsn(Opcodes.ALOAD, 0)
    methodVisitor.visitFieldInsn(
      Opcodes.GETSTATIC,
      "org/elasticsearch/bootstrap/PolicyUtil",
      "ALLOWED_PLUGIN_PERMISSIONS",
      "Lorg/elasticsearch/bootstrap/PolicyUtil$PermissionMatcher;"
    )
    methodVisitor.visitFieldInsn(
      Opcodes.GETFIELD,
      "org/elasticsearch/bootstrap/PolicyUtil$PermissionMatcher",
      "classPermissions",
      "Ljava/util/Map;"
    )
    methodVisitor.visitMethodInsn(
      Opcodes.INVOKESPECIAL,
      "org/elasticsearch/bootstrap/PolicyUtil$PermissionMatcher",
      "<init>",
      "(Ljava/security/PermissionCollection;Ljava/util/Map;)V",
      false
    )
    methodVisitor.visitInsn(Opcodes.ARETURN)
    methodVisitor.visitMaxs(4, 1)
    methodVisitor.visitEnd()

    private def includeAdditionalPermission(methodVisitor: MethodVisitor, permission: Permission): Unit = {
      val jvmStylePermissionClassName = permission.getClass.getName.replace('.', '/')
      methodVisitor.visitVarInsn(Opcodes.ALOAD, 0)
      methodVisitor.visitTypeInsn(Opcodes.NEW, jvmStylePermissionClassName)
      methodVisitor.visitInsn(Opcodes.DUP)
      methodVisitor.visitLdcInsn(permission.getName)
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKESPECIAL,
        jvmStylePermissionClassName,
        "<init>",
        "(Ljava/lang/String;)V",
        false
      )
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "java/security/PermissionCollection",
        "add",
        "(Ljava/security/Permission;)V",
        false
      )
    }
  }

  private class GetPluginPolicyInfoAddingRorExtraPermission(underlying: MethodVisitor)
    extends MethodVisitor(Opcodes.ASM9) {

    override def visitCode(): Unit = {
      underlying.visitCode()
      val label0 = new Label
      underlying.visitLabel(label0)
      underlying.visitLineNumber(370, label0)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        "org/elasticsearch/bootstrap/PolicyUtil",
        "readPolicyInfo",
        "(Ljava/nio/file/Path;)Lorg/elasticsearch/bootstrap/PluginPolicyInfo;",
        false
      )
      underlying.visitVarInsn(Opcodes.ASTORE, 2)
      val label1 = new Label
      underlying.visitLabel(label1)
      underlying.visitLineNumber(371, label1)
      underlying.visitVarInsn(Opcodes.ALOAD, 2)
      val label2 = new Label
      underlying.visitJumpInsn(Opcodes.IFNULL, label2)
      underlying.visitVarInsn(Opcodes.ALOAD, 2)
      underlying.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        "org/elasticsearch/bootstrap/PolicyUtil",
        "isItRorPlugin",
        "(Lorg/elasticsearch/bootstrap/PluginPolicyInfo;)Z",
        false
      )
      underlying.visitJumpInsn(Opcodes.IFEQ, label2)
      val label3 = new Label
      underlying.visitLabel(label3)
      underlying.visitLineNumber(372, label3)
      underlying.visitLdcInsn("plugin")
      underlying.visitVarInsn(Opcodes.ALOAD, 2)
      underlying.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        "org/elasticsearch/bootstrap/PolicyUtil",
        "allowedPluginPermissionsExclusivelyForRor",
        "()Lorg/elasticsearch/bootstrap/PolicyUtil$PermissionMatcher;",
        false
      )
      underlying.visitVarInsn(Opcodes.ALOAD, 1)
      underlying.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        "org/elasticsearch/bootstrap/PolicyUtil",
        "validatePolicyPermissions",
        "(Ljava/lang/String;Lorg/elasticsearch/bootstrap/PluginPolicyInfo;Lorg/elasticsearch/bootstrap/PolicyUtil$PermissionMatcher;Ljava/nio/file/Path;)V",
        false
      )
      val label4 = new Label()
      underlying.visitJumpInsn(Opcodes.GOTO, label4)
      underlying.visitLabel(label2)
      underlying.visitLineNumber(374, label2)
      underlying.visitFrame(
        Opcodes.F_APPEND,
        1,
        Array("org/elasticsearch/bootstrap/PluginPolicyInfo"),
        0,
        null
      )
      underlying.visitLdcInsn("plugin")
      underlying.visitVarInsn(Opcodes.ALOAD, 2)
      underlying.visitFieldInsn(
        Opcodes.GETSTATIC,
        "org/elasticsearch/bootstrap/PolicyUtil",
        "ALLOWED_PLUGIN_PERMISSIONS",
        "Lorg/elasticsearch/bootstrap/PolicyUtil$PermissionMatcher;"
      )
      underlying.visitVarInsn(Opcodes.ALOAD, 1)
      underlying.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        "org/elasticsearch/bootstrap/PolicyUtil",
        "validatePolicyPermissions",
        "(Ljava/lang/String;Lorg/elasticsearch/bootstrap/PluginPolicyInfo;Lorg/elasticsearch/bootstrap/PolicyUtil$PermissionMatcher;Ljava/nio/file/Path;)V",
        false
      )
      underlying.visitLabel(label4)
      underlying.visitLineNumber(376, label4)
      underlying.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
      underlying.visitVarInsn(Opcodes.ALOAD, 2)
      underlying.visitInsn(Opcodes.ARETURN)
      val label5 = new Label()
      underlying.visitLabel(label5)
      underlying.visitLocalVariable("pluginRoot", "Ljava/nio/file/Path;", null, label0, label5, 0)
      underlying.visitLocalVariable("tmpDir", "Ljava/nio/file/Path;", null, label0, label5, 1)
      underlying.visitLocalVariable("info", "Lorg/elasticsearch/bootstrap/PluginPolicyInfo;", null, label1, label5, 2)
      underlying.visitMaxs(4, 3)
      underlying.visitEnd()
    }
  }


}
