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
import tech.beshu.ror.tools.core.utils.EsUtil.{es71722, es800, es8150}

import java.io.{File, InputStream}

private[patches] class SecurityManagerShouldAllowReadingEsConfigFile(esVersion: SemVer)
  extends BytecodeJarModifier {

  private val modifyPermissionsForElasticsearchYmlInSecurityClass = new ModifyPermissionsForElasticsearchYmlInSecurityClass()
  private val modifyPermissionsForElasticsearchYmlInEsPolicyClass = new ModifyPermissionsForElasticsearchYmlInEsPolicyClass()

  override def apply(jar: File): Unit = {
    esVersion match {
      case v if v >= es8150 => modifyPermissionsForElasticsearchYmlInEsPolicyClass(jar)
      case v if v >= es800 => ()
      case v if v >= es71722 => modifyPermissionsForElasticsearchYmlInSecurityClass(jar)
      case _ => ()
    }
  }

}

private class ModifyPermissionsForElasticsearchYmlInEsPolicyClass extends BytecodeJarModifier {

  override def apply(jar: File): Unit = {
    modifyFileInJar(
      jar = jar,
      filePathString = "org/elasticsearch/bootstrap/ESPolicy.class",
      processFileContent = removeForbiddingReadPermissionForElasticsearchYmlFile
    )
  }

  private def removeForbiddingReadPermissionForElasticsearchYmlFile(moduleInputStream: InputStream) = {
    val reader = new ClassReader(moduleInputStream)
    val writer = new ClassWriter(reader, 0)
    reader.accept(new EsClassVisitor(writer), 0)
    writer.toByteArray
  }

  private class EsClassVisitor(writer: ClassWriter)
    extends ClassVisitor(Opcodes.ASM9, writer) {

    new CreateSecuredFilesForRorMethod(this)

    override def visitMethod(access: Int,
                             name: String,
                             descriptor: String,
                             signature: String,
                             exceptions: Array[String]): MethodVisitor = {
      def noChanges = super.visitMethod(access, name, descriptor, signature, exceptions)

      name match {
        case "<init>" =>
          new ElasticsearchYmlFileShouldBeReadable(super.visitMethod(access, name, descriptor, signature, exceptions))
        case _ =>
          noChanges
      }
    }
  }

  private class ElasticsearchYmlFileShouldBeReadable(underlying: MethodVisitor)
    extends MethodVisitor(Opcodes.ASM9, underlying) {

    private var isPolicyTypeAssigned = false

    override def visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String): Unit = {
      if (opcode == Opcodes.PUTFIELD && "securedFiles" == name && !isPolicyTypeAssigned) {
        isPolicyTypeAssigned = true
        visitVarInsn(Opcodes.ALOAD, 0)
        visitVarInsn(Opcodes.ALOAD, 0)
        visitVarInsn(Opcodes.ALOAD, 6)
        visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/bootstrap/ESPolicy", "createSecuredFilesForRor", "(Ljava/util/Map;)Ljava/util/Map;", false)
        visitFieldInsn(Opcodes.PUTFIELD, "org/elasticsearch/bootstrap/ESPolicy", "securedFiles", "Ljava/util/Map;")
      } else {
        super.visitFieldInsn(opcode, owner, name, descriptor)
      }
    }
  }

  private class CreateSecuredFilesForRorMethod(classVisitor: ClassVisitor) {
    private val methodVisitor = classVisitor.visitMethod(Opcodes.ACC_PRIVATE, "createSecuredFilesForRor", "(Ljava/util/Map;)Ljava/util/Map;", "(Ljava/util/Map<Ljava/lang/String;Ljava/util/Set<Ljava/net/URL;>;>;)Ljava/util/Map<Ljava/io/FilePermission;Ljava/util/Set<Ljava/net/URL;>;>;", null)
    methodVisitor.visitCode()
    val label0 = new Label()
    methodVisitor.visitLabel(label0)
    methodVisitor.visitTypeInsn(Opcodes.NEW, "java/util/HashMap")
    methodVisitor.visitInsn(Opcodes.DUP)
    methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false)
    methodVisitor.visitVarInsn(Opcodes.ASTORE, 2)
    val label1 = new Label()
    methodVisitor.visitLabel(label1)
    methodVisitor.visitVarInsn(Opcodes.ALOAD, 1)
    methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "entrySet", "()Ljava/util/Set;", true)
    methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Set", "iterator", "()Ljava/util/Iterator;", true)
    methodVisitor.visitVarInsn(Opcodes.ASTORE, 3)
    val label2 = new Label()
    methodVisitor.visitLabel(label2)
    methodVisitor.visitFrame(Opcodes.F_APPEND, 2, Array("java/util/Map", "java/util/Iterator"), 0, null)
    methodVisitor.visitVarInsn(Opcodes.ALOAD, 3)
    methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true)
    val label3 = new Label()
    methodVisitor.visitJumpInsn(Opcodes.IFEQ, label3)
    methodVisitor.visitVarInsn(Opcodes.ALOAD, 3)
    methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true)
    methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, "java/util/Map$Entry")
    methodVisitor.visitVarInsn(Opcodes.ASTORE, 4)
    val label4 = new Label()
    methodVisitor.visitLabel(label4)
    methodVisitor.visitVarInsn(Opcodes.ALOAD, 4)
    methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map$Entry", "getKey", "()Ljava/lang/Object;", true)
    methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String")
    methodVisitor.visitVarInsn(Opcodes.ASTORE, 5)
    val label5 = new Label()
    methodVisitor.visitLabel(label5)
    methodVisitor.visitTypeInsn(Opcodes.NEW, "java/io/FilePermission")
    methodVisitor.visitInsn(Opcodes.DUP)
    methodVisitor.visitVarInsn(Opcodes.ALOAD, 5)
    methodVisitor.visitLdcInsn("elasticsearch.yml")
    methodVisitor.visitVarInsn(Opcodes.ALOAD, 5)
    methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false)
    val label6 = new Label()
    methodVisitor.visitJumpInsn(Opcodes.IFEQ, label6)
    methodVisitor.visitLdcInsn("write,delete,execute")
    val label7 = new Label()
    methodVisitor.visitJumpInsn(Opcodes.GOTO, label7)
    methodVisitor.visitLabel(label6)
    methodVisitor.visitFrame(Opcodes.F_FULL, 6, Array("org/elasticsearch/bootstrap/ESPolicy", "java/util/Map", "java/util/Map", "java/util/Iterator", "java/util/Map$Entry", "java/lang/String"), 3, Array(label5, label5, "java/lang/String"))
    methodVisitor.visitLdcInsn("read,readlink,write,delete,execute")
    methodVisitor.visitLabel(label7)
    methodVisitor.visitFrame(Opcodes.F_FULL, 6, Array("org/elasticsearch/bootstrap/ESPolicy", "java/util/Map", "java/util/Map", "java/util/Iterator", "java/util/Map$Entry", "java/lang/String"), 4, Array(label5, label5, "java/lang/String", "java / lang / String"))
    methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/io/FilePermission", "<init>", "(Ljava/lang/String;Ljava/lang/String;)V", false)
    methodVisitor.visitVarInsn(Opcodes.ASTORE, 6)
    val label8 = new Label()
    methodVisitor.visitLabel(label8)
    methodVisitor.visitVarInsn(Opcodes.ALOAD, 2)
    methodVisitor.visitVarInsn(Opcodes.ALOAD, 6)
    methodVisitor.visitVarInsn(Opcodes.ALOAD, 4)
    methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map$Entry", "getValue", "()Ljava/lang/Object;", true)
    methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, "java/util/Set")
    methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true)
    methodVisitor.visitInsn(Opcodes.POP)
    val label9 = new Label()
    methodVisitor.visitLabel(label9)
    methodVisitor.visitJumpInsn(Opcodes.GOTO, label2)
    methodVisitor.visitLabel(label3)
    methodVisitor.visitFrame(Opcodes.F_CHOP, 3, null, 0, null)
    methodVisitor.visitVarInsn(Opcodes.ALOAD, 2)
    methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "unmodifiableMap", "(Ljava/util/Map;)Ljava/util/Map;", false)
    methodVisitor.visitInsn(Opcodes.ARETURN)
    val label10 = new Label()
    methodVisitor.visitLabel(label10)
    methodVisitor.visitLocalVariable("key", "Ljava/lang/String;", null, label5, label9, 5)
    methodVisitor.visitLocalVariable("filePermission", "Ljava/io/FilePermission;", null, label8, label9, 6)
    methodVisitor.visitLocalVariable("entry", "Ljava/util/Map$Entry;", "Ljava/util/Map$Entry<Ljava/lang/String;Ljava/util/Set<Ljava/net/URL;>;>;", label4, label9, 4)
    methodVisitor.visitLocalVariable("this", "Lorg/elasticsearch/bootstrap/ESPolicy;", null, label0, label10, 0)
    methodVisitor.visitLocalVariable("securedFiles", "Ljava/util/Map;", "Ljava/util/Map<Ljava/lang/String;Ljava/util/Set<Ljava/net/URL;>;>;", label0, label10, 1)
    methodVisitor.visitLocalVariable("result", "Ljava/util/Map;", "Ljava/util/Map<Ljava/io/FilePermission;Ljava/util/Set<Ljava/net/URL;>;>;", label1, label10, 2)
    methodVisitor.visitMaxs(5, 7)
    methodVisitor.visitEnd()
  }

}

private class ModifyPermissionsForElasticsearchYmlInSecurityClass extends BytecodeJarModifier {

  override def apply(jar: File): Unit = {
    modifyFileInJar(
      jar = jar,
      filePathString = "org/elasticsearch/bootstrap/Security.class",
      processFileContent = removeForbiddingReadPermissionForElasticsearchYmlFile
    )
  }

  private def removeForbiddingReadPermissionForElasticsearchYmlFile(moduleInputStream: InputStream) = {
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
      def noChanges = super.visitMethod(access, name, descriptor, signature, exceptions)

      name match {
        case "createForbiddenFilePermissions" =>
          new ElasticsearchYmlFileShouldBeReadable(super.visitMethod(access, name, descriptor, signature, exceptions))
        case _ =>
          noChanges
      }
    }
  }

  private class ElasticsearchYmlFileShouldBeReadable(underlying: MethodVisitor)
    extends MethodVisitor(Opcodes.ASM9, underlying) {

    private var modifyThePermissionList: Boolean = false

    override def visitLdcInsn(value: Any): Unit = {
      value match {
        case "elasticsearch.yml" =>
          modifyThePermissionList = true
          super.visitLdcInsn(value)
        case "read,readlink,write,delete,execute" if modifyThePermissionList =>
          modifyThePermissionList = false
          super.visitLdcInsn("write,delete,execute")
        case _ =>
          super.visitLdcInsn(value)
      }
    }
  }

}