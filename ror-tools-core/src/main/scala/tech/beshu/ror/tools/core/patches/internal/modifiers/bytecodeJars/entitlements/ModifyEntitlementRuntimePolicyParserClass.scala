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
package tech.beshu.ror.tools.core.patches.internal.modifiers.bytecodeJars.entitlements

import org.objectweb.asm.*
import tech.beshu.ror.tools.core.patches.internal.modifiers.BytecodeJarModifier

import java.io.{File, InputStream}

private [patches] object ModifyEntitlementRuntimePolicyParserClass extends BytecodeJarModifier {

  override def apply(jar: File): Unit = {
    modifyFileInJar(
      jar = jar,
      filePathString = "org/elasticsearch/entitlement/runtime/policy/PolicyParser.class",
      processFileContent = doAddCreateClassLoaderEntitlementForRorPlugin
    )
  }

  private def doAddCreateClassLoaderEntitlementForRorPlugin(moduleInputStream: InputStream) = {
    val reader = new ClassReader(moduleInputStream)
    val writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES)
    reader.accept(new EsClassVisitor(writer), 0)
    writer.toByteArray
  }

  private class EsClassVisitor(writer: ClassWriter)
    extends ClassVisitor(Opcodes.ASM9, writer) {

    override def visit(version: Int, access: Int, name: String, signature: String, superName: String, interfaces: Array[String]): Unit = {
      super.visit(version, access, name, signature, superName, interfaces)
      UpgradeIfRorPolicyNameMethod.create(this)
    }

    override def visitMethod(access: Int,
                             name: String,
                             descriptor: String,
                             signature: String,
                             exceptions: Array[String]): MethodVisitor = {
      name match {
        case "parsePolicy" =>
          new RorScopeUpgradedParsePolicyMethod(
            super.visitMethod(access, name, descriptor, signature, exceptions)
          )
        case _ =>
          super.visitMethod(access, name, descriptor, signature, exceptions)
      }
    }
  }

  private object UpgradeIfRorPolicyNameMethod {

    def create(classVisitor: ClassVisitor): Unit = {
      val methodVisitor = classVisitor.visitMethod(Opcodes.ACC_PRIVATE, "upgradeIfRorPolicyName", "(Ljava/lang/String;Lorg/elasticsearch/entitlement/runtime/policy/Scope;)Lorg/elasticsearch/entitlement/runtime/policy/Scope;", null, null)
      methodVisitor.visitCode()
      val label0 = new Label()
      methodVisitor.visitLabel(label0)
      methodVisitor.visitLineNumber(184, label0)
      methodVisitor.visitLdcInsn("readonlyrest")
      methodVisitor.visitVarInsn(Opcodes.ALOAD, 1)
      methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false)
      val label1 = new Label()
      methodVisitor.visitJumpInsn(Opcodes.IFEQ, label1)
      methodVisitor.visitLdcInsn("ALL-UNNAMED")
      methodVisitor.visitVarInsn(Opcodes.ALOAD, 2)
      methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/entitlement/runtime/policy/Scope", "moduleName", "()Ljava/lang/String;", false)
      methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false)
      methodVisitor.visitJumpInsn(Opcodes.IFEQ, label1)
      val label2 = new Label()
      methodVisitor.visitLabel(label2)
      methodVisitor.visitLineNumber(185, label2)
      methodVisitor.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList")
      methodVisitor.visitInsn(Opcodes.DUP)
      methodVisitor.visitVarInsn(Opcodes.ALOAD, 2)
      methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/entitlement/runtime/policy/Scope", "entitlements", "()Ljava/util/List;", false)
      methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "(Ljava/util/Collection;)V", false)
      methodVisitor.visitVarInsn(Opcodes.ASTORE, 3)
      val label3 = new Label()
      methodVisitor.visitLabel(label3)
      methodVisitor.visitLineNumber(186, label3)
      methodVisitor.visitVarInsn(Opcodes.ALOAD, 3)
      methodVisitor.visitTypeInsn(Opcodes.NEW, "org/elasticsearch/entitlement/runtime/policy/entitlements/CreateClassLoaderEntitlement")
      methodVisitor.visitInsn(Opcodes.DUP)
      methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/elasticsearch/entitlement/runtime/policy/entitlements/CreateClassLoaderEntitlement", "<init>", "()V", false)
      methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true)
      methodVisitor.visitInsn(Opcodes.POP)
      val label4 = new Label()
      methodVisitor.visitLabel(label4)
      methodVisitor.visitLineNumber(187, label4)
      methodVisitor.visitTypeInsn(Opcodes.NEW, "org/elasticsearch/entitlement/runtime/policy/Scope")
      methodVisitor.visitInsn(Opcodes.DUP)
      methodVisitor.visitVarInsn(Opcodes.ALOAD, 2)
      methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/entitlement/runtime/policy/Scope", "moduleName", "()Ljava/lang/String;", false)
      methodVisitor.visitVarInsn(Opcodes.ALOAD, 3)
      methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/elasticsearch/entitlement/runtime/policy/Scope", "<init>", "(Ljava/lang/String;Ljava/util/List;)V", false)
      methodVisitor.visitInsn(Opcodes.ARETURN)
      methodVisitor.visitLabel(label1)
      methodVisitor.visitLineNumber(189, label1)
      methodVisitor.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
      methodVisitor.visitVarInsn(Opcodes.ALOAD, 2)
      methodVisitor.visitInsn(Opcodes.ARETURN)
      val label5 = new Label()
      methodVisitor.visitLabel(label5)
      methodVisitor.visitLocalVariable("extendedEntitlements", "Ljava/util/List;", "Ljava/util/List<Lorg/elasticsearch/entitlement/runtime/policy/entitlements/Entitlement;>;", label3, label1, 3)
      methodVisitor.visitLocalVariable("this", "Lorg/elasticsearch/entitlement/runtime/policy/PolicyParser;", null, label0, label5, 0)
      methodVisitor.visitLocalVariable("policyName", "Ljava/lang/String;", null, label0, label5, 1)
      methodVisitor.visitLocalVariable("scope", "Lorg/elasticsearch/entitlement/runtime/policy/Scope;", null, label0, label5, 2)
      methodVisitor.visitMaxs(4, 4)
      methodVisitor.visitEnd()
    }
  }

  private class RorScopeUpgradedParsePolicyMethod(underlying: MethodVisitor)
    extends MethodVisitor(Opcodes.ASM9) {

    override def visitCode(): Unit = {
      underlying.visitCode()
      val label0 = new Label()
      val label1 = new Label()
      val label2 = new Label()
      underlying.visitTryCatchBlock(label0, label1, label2, "java/io/IOException")
      underlying.visitLabel(label0)
      underlying.visitLineNumber(165, label0)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitFieldInsn(Opcodes.GETFIELD, "org/elasticsearch/entitlement/runtime/policy/PolicyParser", "policyParser", "Lorg/elasticsearch/xcontent/XContentParser;")
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/elasticsearch/xcontent/XContentParser", "nextToken", "()Lorg/elasticsearch/xcontent/XContentParser$Token;", true)
      underlying.visitFieldInsn(Opcodes.GETSTATIC, "org/elasticsearch/xcontent/XContentParser$Token", "START_OBJECT", "Lorg/elasticsearch/xcontent/XContentParser$Token;")
      val label3 = new Label()
      underlying.visitJumpInsn(Opcodes.IF_ACMPEQ, label3)
      val label4 = new Label()
      underlying.visitLabel(label4)
      underlying.visitLineNumber(166, label4)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitLdcInsn("expected object <scope name>")
      underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/entitlement/runtime/policy/PolicyParser", "newPolicyParserException", "(Ljava/lang/String;)Lorg/elasticsearch/entitlement/runtime/policy/PolicyParserException;", false)
      underlying.visitInsn(Opcodes.ATHROW)
      underlying.visitLabel(label3)
      underlying.visitLineNumber(168, label3)
      underlying.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
      underlying.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList")
      underlying.visitInsn(Opcodes.DUP)
      underlying.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false)
      underlying.visitVarInsn(Opcodes.ASTORE, 1)
      val label5 = new Label()
      underlying.visitLabel(label5)
      underlying.visitLineNumber(169, label5)
      underlying.visitFrame(Opcodes.F_APPEND, 1, Array("java/util/List"), 0, null)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitFieldInsn(Opcodes.GETFIELD, "org/elasticsearch/entitlement/runtime/policy/PolicyParser", "policyParser", "Lorg/elasticsearch/xcontent/XContentParser;")
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/elasticsearch/xcontent/XContentParser", "nextToken", "()Lorg/elasticsearch/xcontent/XContentParser$Token;", true)
      underlying.visitFieldInsn(Opcodes.GETSTATIC, "org/elasticsearch/xcontent/XContentParser$Token", "END_OBJECT", "Lorg/elasticsearch/xcontent/XContentParser$Token;")
      val label6 = new Label()
      underlying.visitJumpInsn(Opcodes.IF_ACMPEQ, label6)
      val label7 = new Label()
      underlying.visitLabel(label7)
      underlying.visitLineNumber(170, label7)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitFieldInsn(Opcodes.GETFIELD, "org/elasticsearch/entitlement/runtime/policy/PolicyParser", "policyParser", "Lorg/elasticsearch/xcontent/XContentParser;")
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/elasticsearch/xcontent/XContentParser", "currentToken", "()Lorg/elasticsearch/xcontent/XContentParser$Token;", true)
      underlying.visitFieldInsn(Opcodes.GETSTATIC, "org/elasticsearch/xcontent/XContentParser$Token", "FIELD_NAME", "Lorg/elasticsearch/xcontent/XContentParser$Token;")
      val label8 = new Label()
      underlying.visitJumpInsn(Opcodes.IF_ACMPEQ, label8)
      val label9 = new Label()
      underlying.visitLabel(label9)
      underlying.visitLineNumber(171, label9)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitLdcInsn("expected object <scope name>")
      underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/entitlement/runtime/policy/PolicyParser", "newPolicyParserException", "(Ljava/lang/String;)Lorg/elasticsearch/entitlement/runtime/policy/PolicyParserException;", false)
      underlying.visitInsn(Opcodes.ATHROW)
      underlying.visitLabel(label8)
      underlying.visitLineNumber(173, label8)
      underlying.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitFieldInsn(Opcodes.GETFIELD, "org/elasticsearch/entitlement/runtime/policy/PolicyParser", "policyParser", "Lorg/elasticsearch/xcontent/XContentParser;")
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/elasticsearch/xcontent/XContentParser", "currentName", "()Ljava/lang/String;", true)
      underlying.visitVarInsn(Opcodes.ASTORE, 2)
      val label10 = new Label()
      underlying.visitLabel(label10)
      underlying.visitLineNumber(174, label10)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitFieldInsn(Opcodes.GETFIELD, "org/elasticsearch/entitlement/runtime/policy/PolicyParser", "policyName", "Ljava/lang/String;")
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitVarInsn(Opcodes.ALOAD, 2)
      underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/entitlement/runtime/policy/PolicyParser", "parseScope", "(Ljava/lang/String;)Lorg/elasticsearch/entitlement/runtime/policy/Scope;", false)
      underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/entitlement/runtime/policy/PolicyParser", "upgradeIfRorPolicyName", "(Ljava/lang/String;Lorg/elasticsearch/entitlement/runtime/policy/Scope;)Lorg/elasticsearch/entitlement/runtime/policy/Scope;", false)
      underlying.visitVarInsn(Opcodes.ASTORE, 3)
      val label11 = new Label()
      underlying.visitLabel(label11)
      underlying.visitLineNumber(175, label11)
      underlying.visitVarInsn(Opcodes.ALOAD, 1)
      underlying.visitVarInsn(Opcodes.ALOAD, 3)
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true)
      underlying.visitInsn(Opcodes.POP)
      val label12 = new Label()
      underlying.visitLabel(label12)
      underlying.visitLineNumber(176, label12)
      underlying.visitJumpInsn(Opcodes.GOTO, label5)
      underlying.visitLabel(label6)
      underlying.visitLineNumber(177, label6)
      underlying.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
      underlying.visitTypeInsn(Opcodes.NEW, "org/elasticsearch/entitlement/runtime/policy/Policy")
      underlying.visitInsn(Opcodes.DUP)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitFieldInsn(Opcodes.GETFIELD, "org/elasticsearch/entitlement/runtime/policy/PolicyParser", "policyName", "Ljava/lang/String;")
      underlying.visitVarInsn(Opcodes.ALOAD, 1)
      underlying.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/elasticsearch/entitlement/runtime/policy/Policy", "<init>", "(Ljava/lang/String;Ljava/util/List;)V", false)
      underlying.visitLabel(label1)
      underlying.visitInsn(Opcodes.ARETURN)
      underlying.visitLabel(label2)
      underlying.visitLineNumber(178, label2)
      underlying.visitFrame(
        Opcodes.F_FULL,
        1,
        Array("org/elasticsearch/entitlement/runtime/policy/PolicyParser"),
        1,
        Array("java/io/IOException")
      )
      underlying.visitVarInsn(Opcodes.ASTORE, 1)
      val label13 = new Label()
      underlying.visitLabel(label13)
      underlying.visitLineNumber(179, label13)
      underlying.visitTypeInsn(Opcodes.NEW, "java/io/UncheckedIOException")
      underlying.visitInsn(Opcodes.DUP)
      underlying.visitVarInsn(Opcodes.ALOAD, 1)
      underlying.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/io/UncheckedIOException", "<init>", "(Ljava/io/IOException;)V", false)
      underlying.visitInsn(Opcodes.ATHROW)
      val label14 = new Label()
      underlying.visitLabel(label14)
      underlying.visitLocalVariable("scopeName", "Ljava/lang/String;", null, label10, label12, 2)
      underlying.visitLocalVariable("scope", "Lorg/elasticsearch/entitlement/runtime/policy/Scope;", null, label11, label12, 3)
      underlying.visitLocalVariable("scopes", "Ljava/util/List;", "Ljava/util/List<Lorg/elasticsearch/entitlement/runtime/policy/Scope;>;", label5, label2, 1)
      underlying.visitLocalVariable("ioe", "Ljava/io/IOException;", null, label13, label14, 1)
      underlying.visitLocalVariable("this", "Lorg/elasticsearch/entitlement/runtime/policy/PolicyParser;", null, label0, label14, 0)
      underlying.visitMaxs(4, 4)
      underlying.visitEnd()
    }
  }

}
