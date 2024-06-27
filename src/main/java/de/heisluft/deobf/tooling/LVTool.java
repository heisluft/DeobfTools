package de.heisluft.deobf.tooling;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import static org.objectweb.asm.Opcodes.*;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class LVTool implements Util {
  static class FlowGraph<E> {
    private final Node<E> rootNode;
    private final Map<E, Node<E>> nodes = new HashMap<>();

    private FlowGraph(E rootValue) {
      rootNode = new Node<>(rootValue);
      nodes.put(rootValue, rootNode);
    }

    void addEdge(E value, E from) {
      nodes.putIfAbsent(value, new Node<>(value));
      nodes.putIfAbsent(from, new Node<>(from));
      Node<E> valNode = nodes.get(value);
      Node<E> fromNode = from == null ? rootNode : nodes.get(from);
      List<Node<E>> conns = fromNode.pointsTo;
      List<Node<E>> reverseConns = valNode.pointedToBy;
      if(!conns.contains(valNode)) conns.add(valNode);
      if(!reverseConns.contains(fromNode)) reverseConns.add(fromNode);
    }
  }
  static class Node<E> {
    private final List<Node<E>> pointsTo = new ArrayList<>();
    private final List<Node<E>> pointedToBy = new ArrayList<>();
    private final E value;
    private Node(E value) {
      this.value = value;
    }
  }


  private final JDKClassProvider classProvider = new JDKClassProvider();
  private final Map<String, ClassNode> classes;


  public static void main(String[] args) throws IOException {
    new LVTool().detect();
  }

  private static class Visitor extends MethodVisitor {
    private final FlowGraph<String> graph = new FlowGraph<>("root");
    Label currentLabel;
    boolean isGoto;
    boolean isStatic;
    final String name;
    Map<Integer, Integer> usedLocals = new HashMap<>();
    Set<Integer> inited = new HashSet<>();
    Map<Integer, String> localClasses = new HashMap<>();
    public Visitor(ClassNode node, MethodNode mn) {
      super(ASM9);
      name = node.name.replace('/', '_').replace('$', '_') + "_" + mn.name.replace("<init>", "_construct").replace("<clinit>", "_class_init");
    }

    @Override
    public void visitVarInsn(int opcode, int varIndex) {
      if(opcode > ALOAD) {
        inited.add(varIndex);
        return;
      }
      else if(inited.contains(varIndex)) return;
      int type = Type.VOID;
      switch(opcode) {
        case ALOAD: type = Type.OBJECT; break;
        case DLOAD: type = Type.DOUBLE; break;
        case FLOAD: type = Type.FLOAT; break;
        case ILOAD: type = Type.INT; break;
        case LLOAD: type = Type.LONG; break;
      }
      if(usedLocals.containsKey(varIndex) && usedLocals.get(varIndex) != type)
        throw new RuntimeException("Wellp");
      usedLocals.put(varIndex, type);
    }

    @Override
    public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
      System.out.println(start.toString() + " " + end.toString() + " " + handler + " " + type);
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
      graph.addEdge(label.toString(), currentLabel != null ? currentLabel.toString() : "root");
      if(opcode == GOTO) isGoto = true;
    }

    @Override
    public void visitEnd() {
      System.out.println("digraph " + name + " {");
      graph.nodes.values().forEach(n -> n.pointsTo.forEach(s -> System.out.println(n.value + "->" + s.value)));
      System.out.println("}\n");
      super.visitEnd();
    }

    @Override
    public void visitInsn(int opcode) {
      if(opcode == ATHROW) graph.addEdge("throw", currentLabel != null ? currentLabel.toString() : "root");
      else if(opcode >= IRETURN && opcode <= RETURN) graph.addEdge("return", currentLabel != null ? currentLabel.toString() : "root");
    }

    @Override
    public void visitLabel(Label label) {
      if(!isGoto) graph.addEdge(label.toString(), currentLabel == null ? null : currentLabel.toString());
      isGoto = false;
      this.currentLabel = label;
      System.out.println(usedLocals);
      usedLocals.clear();
    }
  }

  private void detect() {
    classes.values().forEach(node -> node.methods.forEach(mn -> mn.accept(new Visitor(node, mn))));
  }

  LVTool() throws IOException {
    classes = parseClasses(Paths.get("remap-tests/jars/mc/client/classic/c0.29_01.jar"));
  }
}
