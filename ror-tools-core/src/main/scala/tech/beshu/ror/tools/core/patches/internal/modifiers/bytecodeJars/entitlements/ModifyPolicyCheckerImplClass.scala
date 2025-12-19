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

import just.semver.SemVer
import org.objectweb.asm.*
import org.objectweb.asm.tree.*
import tech.beshu.ror.tools.core.patches.internal.modifiers.BytecodeJarModifier
import tech.beshu.ror.tools.core.utils.EsUtil.{es8199, es900, es923}

import java.io.{File, InputStream}

private[patches] class ModifyPolicyCheckerImplClass(esVersion: SemVer)
  extends BytecodeJarModifier {

  override def apply(jar: File): Unit = {
    modifyFileInJar(
      jar = jar,
      filePathString = "org/elasticsearch/entitlement/runtime/policy/PolicyCheckerImpl.class",
      processFileContent = dontValidateFileAccessInCaseOfRorPlugin
    )
  }

  private def dontValidateFileAccessInCaseOfRorPlugin(moduleInputStream: InputStream) = {
    val reader = new ClassReader(moduleInputStream)
    val writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES)
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
        case "checkFileRead" =>
          esVersion match {
            case v if v >= es923 =>
              val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
              new CheckFileReadMethodShouldNotValidateFileAccessInCaseOfRorPlugin(access, name, descriptor, signature, exceptions, mv)
            case v if v >= es900 =>
              super.visitMethod(access, name, descriptor, signature, exceptions)
            case v if v >= es8199 =>
              val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
              new CheckFileReadMethodShouldNotValidateFileAccessInCaseOfRorPlugin(access, name, descriptor, signature, exceptions, mv)
            case v =>
              super.visitMethod(access, name, descriptor, signature, exceptions)
          }
        case _ =>
          super.visitMethod(access, name, descriptor, signature, exceptions)
      }
    }
  }

  private class CheckFileReadMethodShouldNotValidateFileAccessInCaseOfRorPlugin(access: Int,
                                                                                name: String,
                                                                                desc: String,
                                                                                signature: String,
                                                                                exceptions: Array[String],
                                                                                underlying: MethodVisitor)
    extends MethodNode(Opcodes.ASM9, access, name, desc, signature, exceptions) {

    override def visitEnd(): Unit = {
      patchAllCanReadCalls()
      accept(underlying)
    }

    private def patchAllCanReadCalls(): Unit = {
      val insns = instructions
      var insn = insns.getFirst

      while (insn != null) {
        val nextInsn = insn.getNext

        insn match {
          case m: MethodInsnNode
            if m.name == "canRead" && m.desc == "(Ljava/nio/file/Path;)Z" =>

            val loadPath = m.getPrevious
            val fileAccessCall = if (loadPath != null) loadPath.getPrevious else null
            val loadEnt = if (fileAccessCall != null) fileAccessCall.getPrevious else null

            (loadEnt, fileAccessCall, loadPath) match {
              case (ve: VarInsnNode, fa: MethodInsnNode, vp: VarInsnNode)
                if ve.getOpcode == Opcodes.ALOAD &&
                  vp.getOpcode == Opcodes.ALOAD &&
                  fa.name == "fileAccess" =>

                val entIdx = ve.`var`
                val pathIdx = vp.`var`

                val lElse = new LabelNode()
                val lEnd = new LabelNode()

                val replacement = new InsnList()

                // "readonlyrest".equals(entitlements.componentName())
                replacement.add(new LdcInsnNode("readonlyrest"))
                replacement.add(new VarInsnNode(Opcodes.ALOAD, entIdx))
                replacement.add(new MethodInsnNode(
                  Opcodes.INVOKEVIRTUAL,
                  fa.owner,
                  "componentName",
                  "()Ljava/lang/String;",
                  false
                ))
                replacement.add(new MethodInsnNode(
                  Opcodes.INVOKEVIRTUAL,
                  "java/lang/String",
                  "equals",
                  "(Ljava/lang/Object;)Z",
                  false
                ))

                // if false -> else branch
                replacement.add(new JumpInsnNode(Opcodes.IFEQ, lElse))

                // true branch => push true
                replacement.add(new InsnNode(Opcodes.ICONST_1))
                replacement.add(new JumpInsnNode(Opcodes.GOTO, lEnd))

                // else branch => original entitlements.fileAccess().canRead(path)
                replacement.add(lElse)
                replacement.add(new VarInsnNode(Opcodes.ALOAD, entIdx))
                replacement.add(new MethodInsnNode(fa.getOpcode, fa.owner, fa.name, fa.desc, fa.itf))
                replacement.add(new VarInsnNode(Opcodes.ALOAD, pathIdx))
                replacement.add(new MethodInsnNode(m.getOpcode, m.owner, m.name, m.desc, m.itf))

                replacement.add(lEnd)

                // Insert replacement before the start of the old sequence, then remove old nodes
                insns.insertBefore(ve, replacement)
                insns.remove(ve)
                insns.remove(fa)
                insns.remove(vp)
                insns.remove(m)
              case _ => // pattern didn't match; do nothing
            }
          case _ => // ignore
        }
        insn = nextInsn
      }
    }
  }
}
