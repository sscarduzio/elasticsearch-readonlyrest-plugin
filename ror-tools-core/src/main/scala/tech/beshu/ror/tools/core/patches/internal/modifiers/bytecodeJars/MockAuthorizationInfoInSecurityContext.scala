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
import java.nio.file.Files

private [patches] object MockAuthorizationInfoInSecurityContext extends BytecodeJarModifier {

  override def apply(jar: File): Unit = {
    val originalFileOwner = Files.getOwner(jar.toPath)
    val modifiedSecurityClass = loadAndProcessFileFromJar(
      jar = jar,
      classFileName = "org/elasticsearch/xpack/core/security/SecurityContext",
      processFileContent = doMockAuthorizationInfo
    )
    updateFileInJar(
      jar = jar,
      destinationPathSting = "org/elasticsearch/xpack/core/security/SecurityContext.class",
      newContent = modifiedSecurityClass
    )
    Files.setOwner(jar.toPath, originalFileOwner)
  }

  private def doMockAuthorizationInfo(moduleInputStream: InputStream) = {
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
        case "getAuthorizationInfoFromContext" =>
          new GetAuthorizationInfoFromContextReturningMockAuthorizationInfo(super.visitMethod(access, name, descriptor, signature, exceptions))
        case _ =>
          super.visitMethod(access, name, descriptor, signature, exceptions)
      }
    }
  }

  private class GetAuthorizationInfoFromContextReturningMockAuthorizationInfo(underlying: MethodVisitor)
    extends MethodVisitor(Opcodes.ASM9) {

    override def visitCode(): Unit = {
      underlying.visitCode()
      val label0 = new Label
      underlying.visitLabel(label0)
      underlying.visitFieldInsn(Opcodes.GETSTATIC, "org/elasticsearch/xpack/core/security/authz/AuthorizationEngine$EmptyAuthorizationInfo", "INSTANCE", "Lorg/elasticsearch/xpack/core/security/authz/AuthorizationEngine$EmptyAuthorizationInfo;")
      underlying.visitInsn(Opcodes.ARETURN)
      val label1 = new Label
      underlying.visitLabel(label1)
      underlying.visitLocalVariable("this", "Lorg/elasticsearch/xpack/core/security/SecurityContext;", null, label0, label1, 0)
      underlying.visitMaxs(1, 1)
      underlying.visitEnd()
    }
  }
}
