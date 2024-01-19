package de.heisluft.deobf.tooling;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Map;

public class LVTool implements Util {
  private final JDKClassProvider classProvider = new JDKClassProvider();
  private final Map<String, ClassNode> classes;

  public static void main(String[] args) throws IOException {
    new LVTool().detect();
  }

  private static class Visitor extends MethodVisitor {
    private Visitor() {
      super(Opcodes.ASM9);
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
      super.visitJumpInsn(opcode, label);
    }
  }

  private void detect() {
    classes.values().forEach(node -> node.methods.forEach(mn -> mn.accept(new Visitor())));
  }

  LVTool() throws IOException {
    classes = parseClasses(Paths.get("remap-tests/jars/mc/client/alpha/a1.1.2_01.jar"));
  }
}
