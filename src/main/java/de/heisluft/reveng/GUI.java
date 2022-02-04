package de.heisluft.reveng;

import de.heisluft.function.Tuple2;
import org.objectweb.asm.tree.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static de.heisluft.function.FunctionalUtil.*;

public class GUI implements Util {
  private JFrame jf;
  private final Map<String, ClassNode> nameLookup = new HashMap<>();
  private final Map<String, Map<String, List<String>>> fieldRelations = new HashMap<>();
  private final Map<String, Map<String, Set<String>>> methodRelations = new HashMap<>();
  private final Map<String, Map<String, MethodNode>> methodLookup = new HashMap<>();
  private final Map<String, List<String>> hierarchy = new HashMap<>();

  private GUI() {
    jf = new JFrame("GUI Test");
    jf.setSize(1280, 720);
    jf.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    Dimension d = div(sub(Toolkit.getDefaultToolkit().getScreenSize(), jf.getSize()), 2);
    jf.setLocation(d.width, d.height);
    jf.addKeyListener(kl(null, Map_of(
        new Tuple2<>(KeyEvent.VK_ESCAPE, e -> jf.dispose()),
        new Tuple2<>(KeyEvent.VK_O, e -> {
          if (e.isControlDown()) System.out.println("Open Op");
        })
    ), null));

    jf.setVisible(true);
    try (FileSystem fs = createFS(Paths.get("inf-20100618-deobf.jar"))) {
      Files.walk(fs.getPath("/")).filter(path -> path.toString().endsWith(".class")).map(thr(this::parseClass)).forEach(cn -> {
        nameLookup.put(cn.name, cn);
      });
    } catch (IOException e) {
      e.printStackTrace();
    }
    nameLookup.values().forEach(classNode -> {
      if(nameLookup.containsKey(classNode.superName)) getOrPut(hierarchy, classNode.name, new ArrayList<>()).add(classNode.superName);
      classNode.interfaces.stream().filter(nameLookup::containsKey).forEach(getOrPut(hierarchy, classNode.name, new ArrayList<>())::add);
      classNode.fields.stream().filter(fieldNode -> fieldNode.desc.contains("L")).forEach(fieldNode -> {
        String cn = fieldNode.desc.substring(fieldNode.desc.startsWith("[") ? fieldNode.desc.lastIndexOf('[') + 2 : 1, fieldNode.desc.length() - 1);
        if (!nameLookup.containsKey(cn)) return;
        getOrPut(getOrPut(fieldRelations, cn, new HashMap<>()), classNode.name, new ArrayList<>()).add(fieldNode.name);
      });
      classNode.methods.forEach(methodNode -> {
        getOrPut(methodLookup, classNode.name, new HashMap<>()).put(methodNode.name+methodNode.desc, methodNode);
        methodNode.instructions.forEach(in -> {
          if (in instanceof TypeInsnNode) {
            String cn = ((TypeInsnNode) in).desc;
            if (nameLookup.containsKey(cn)) {
              getOrPut(getOrPut(methodRelations, cn, new HashMap<>()), classNode.name, new HashSet<>()).add(methodNode.name + methodNode.desc);
            }
          }
          if (in instanceof MultiANewArrayInsnNode) {
            String desc = ((MultiANewArrayInsnNode) in).desc;
            String cn = desc.substring(desc.startsWith("[") ? desc.lastIndexOf('[') + 2 : 1, desc.length() - (desc.endsWith(";") ? 1 : 0));
            if (nameLookup.containsKey(cn)) {
              getOrPut(getOrPut(methodRelations, cn, new HashMap<>()), classNode.name, new HashSet<>()).add(methodNode.name + methodNode.desc);
            }
          }
          if (in instanceof FieldInsnNode) {
            String desc = ((FieldInsnNode) in).desc;
            String cn = desc.substring(desc.startsWith("[") ? desc.lastIndexOf('[') + 2 : 1, desc.length() - (desc.endsWith(";") ? 1 : 0));
            if (nameLookup.containsKey(cn)) {
              getOrPut(getOrPut(methodRelations, cn, new HashMap<>()), classNode.name, new HashSet<>()).add(methodNode.name + methodNode.desc);
            }
          }
          if (in instanceof MethodInsnNode) {
            String cn = ((MethodInsnNode) in).owner;
            if (nameLookup.containsKey(cn)) {
              getOrPut(getOrPut(methodRelations, cn, new HashMap<>()), classNode.name, new HashSet<>()).add(methodNode.name + methodNode.desc);
            }
          }
        });
      });
    });
    stream(methodRelations).filter(tuple -> tuple._2.size() < 2).filter(tuple-> stream(tuple._2).allMatch(t -> t._2.size() == 1)).filter(tuple -> !fieldRelations.containsKey(tuple._1)).sorted(Comparator.comparing(o -> o._1)).map(tuple -> tuple.map((s, map) -> s + " -> " + stream(map).map(t -> t.map((s1, strings) -> s1 + "#" + strings.iterator().next())).findFirst().get())).forEach(System.out::println);
  }

  private static <T1,T2> Stream<Tuple2<T1, T2>> stream(Map<T1, T2> d) {
    return d.entrySet().stream().map(Tuple2::new);
  }

  public static void main(String[] args) {
    new GUI();
  }

  private static KeyListener kl(Consumer<KeyEvent> typed, Map<Integer, Consumer<KeyEvent>> pressed, Consumer<KeyEvent> released) {
    return new KeyListener() {

      @Override
      public void keyTyped(KeyEvent e) {
        if (typed != null) typed.accept(e);
      }

      @Override
      public void keyPressed(KeyEvent e) {
        if (pressed != null) pressed.getOrDefault(e.getKeyCode(), ignored -> {}).accept(e);
      }

      @Override
      public void keyReleased(KeyEvent e) {
        if (released != null) released.accept(e);
      }
    };
  }

  private static Dimension div(Dimension d, int by) {
    return new Dimension(d.width / by, d.height / by);
  }

  // Code style violation in favor of J9 API lookalike
  @SafeVarargs
  private static <K,V> Map<K,V> Map_of(Tuple2<K,V>... tups) {
    Map<K, V> res = new HashMap<>(tups.length);
    for (Tuple2<K, V> t : tups) res.put(t._1,t._2);
    return res;
  }

  private static Dimension sub(Dimension d, Dimension o) {
    return new Dimension(d.width - o.width, d.height - o.height);
  }
}
