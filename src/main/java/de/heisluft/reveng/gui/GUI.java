package de.heisluft.reveng.gui;

import de.heisluft.function.Tuple2;
import de.heisluft.reveng.Analyzer;
import de.heisluft.reveng.Util;
import de.heisluft.stream.BiStream;
import org.objectweb.asm.tree.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static de.heisluft.function.FunctionalUtil.*;

public class GUI implements Util {
  private JFrame jf;
  private JFileChooser chooser = new JFileChooser(".");

  private final Map<String, ClassNode> nameLookup = new HashMap<>();
  private final Map<String, Map<String, List<String>>> fieldRelations = new HashMap<>();
  private final Map<String, Map<String, Set<String>>> methodRelations = new HashMap<>();
  private final Map<String, Map<String, MethodNode>> methodLookup = new HashMap<>();
  private final Map<String, List<String>> hierarchy = new HashMap<>();

  private GUI() throws UnsupportedLookAndFeelException, ReflectiveOperationException {
    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
    jf = new JFrame("GUI Test");
    jf.setSize(1280, 720);
    jf.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    Dimension d = div(sub(Toolkit.getDefaultToolkit().getScreenSize(), jf.getSize()), 2);
    initMenuBar();
    jf.setLocation(d.width, d.height);
    jf.addKeyListener(kl(null, Map_of(
        new Tuple2<>(KeyEvent.VK_ESCAPE, e -> jf.dispose()),
        new Tuple2<>(KeyEvent.VK_O, e -> {
          if (e.isControlDown()) showOpenDialog();
        })
    ), null));
    jf.setVisible(true);
  }

  private void initMenuBar() {
    JMenuBar jmb = new JMenuBar();
    JMenu fileJm = new JMenu("File");
    JMenuItem openJmi = new JMenuItem("Open");
    openJmi.addActionListener(e -> {
      showOpenDialog();
    });
    fileJm.add(openJmi);
    jmb.add(fileJm);
    jf.setJMenuBar(jmb);
  }

  private void showOpenDialog() {
    if(chooser.showOpenDialog(jf) == JFileChooser.APPROVE_OPTION) openJarFile(chooser.getSelectedFile().toPath());
  }

  private void openJarFile(Path path) {
    try {
      nameLookup.putAll(parseClasses(path));
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
    BiStream.streamMap(methodRelations)
        .filter2(map -> map.size() < 2 && map.values().stream().allMatch(set -> set.size() == 1))
        .filter1(not(fieldRelations::containsKey))
        .sorted(Comparator.comparing(Tuple2::_1))
        .map2(map -> BiStream.streamMap(map).map2(GUI::getFirst).map(join("#")).iterator().next())
        .map(join(" -> "))
        .forEach(System.out::println);
  }

  private static ThrowingBiFunction<String, String, String> join(String infix) {
    return (s, s2) -> s + infix + s2;
  }

  private static  <T> T getFirst(Set<T> collection) {
    return collection.iterator().next();
  }

  public static void main(String[] args) throws UnsupportedLookAndFeelException, ReflectiveOperationException {
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
