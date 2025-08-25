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
import tech.beshu.ror.tools.core.utils.EsUtil.es790

import java.io.{File, InputStream}

/*
  RepositoriesService is not updated with cluster events for certain type of nodes. ROR needs the up-to-date
  RepositoriesService to handle repositories and snapshots. In this bytecode modifier we remove the conditional check
  which was responsible of disabling cluster events update on RepositoriesService instance.
 */
private [patches] class RepositoriesServiceAvailableForClusterServiceForAnyTypeOfNode(esVersion: SemVer)
  extends BytecodeJarModifier {

  override def apply(jar: File): Unit = {
    modifyFileInJar(
      jar = jar,
      filePathString = "org/elasticsearch/repositories/RepositoriesService.class",
      processFileContent = enableClusterEventsHandling
    )
  }

  private def enableClusterEventsHandling(moduleInputStream: InputStream) = {
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
        case "<init>" =>
          esVersion match {
            case v if v >= es790 =>
              new ConstructorWithAlwaysAddingHighPriorityApplier(super.visitMethod(access, name, descriptor, signature, exceptions))
            case _ =>
              new ConstructorWithAlwaysAddingStateApplier(super.visitMethod(access, name, descriptor, signature, exceptions))
          }
        case _ =>
          super.visitMethod(access, name, descriptor, signature, exceptions)
      }
    }
  }

  private class ConstructorWithAlwaysAddingHighPriorityApplier(underlying: MethodVisitor)
    extends MethodVisitor(Opcodes.ASM9, underlying) {

    override def visitInsn(opcode: Int): Unit = {
      if (opcode == Opcodes.RETURN) {
        underlying.visitVarInsn(Opcodes.ALOAD, 2)
        underlying.visitVarInsn(Opcodes.ALOAD, 0)
        underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/cluster/service/ClusterService", "addHighPriorityApplier", "(Lorg/elasticsearch/cluster/ClusterStateApplier;)V", false);
      }
      underlying.visitInsn(opcode)
    }
  }

  private class ConstructorWithAlwaysAddingStateApplier(underlying: MethodVisitor)
    extends MethodVisitor(Opcodes.ASM9, underlying) {

    override def visitInsn(opcode: Int): Unit = {
      if (opcode == Opcodes.RETURN) {
        underlying.visitVarInsn(Opcodes.ALOAD, 2)
        underlying.visitVarInsn(Opcodes.ALOAD, 0)
        underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/cluster/service/ClusterService", "addStateApplier", "(Lorg/elasticsearch/cluster/ClusterStateApplier;)V", false);
      }
      underlying.visitInsn(opcode)
    }
  }
}
