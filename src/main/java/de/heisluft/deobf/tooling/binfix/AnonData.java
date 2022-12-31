package de.heisluft.deobf.tooling.binfix;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InnerClassNode;

import java.util.Objects;

public final class AnonData {

  public static final AnonData NULL_DATA = new AnonData(null, null, null);

  public final String outerClass, outerMethod, outerMethodDesc;

  @Override
  public boolean equals(Object o) {
    if(this == o) return true;
    if(o == null || getClass() != o.getClass()) return false;
    AnonData anonData = (AnonData) o;
    return Objects.equals(outerClass, anonData.outerClass) &&
        Objects.equals(outerMethod, anonData.outerMethod) &&
        Objects.equals(outerMethodDesc, anonData.outerMethodDesc);
  }

  @Override
  public int hashCode() {
    return Objects.hash(outerClass, outerMethod, outerMethodDesc);
  }

  public AnonData(String outerClass, String outerMethod, String outerMethodDesc) {
    this.outerClass = outerClass;
    this.outerMethod = outerMethod;
    this.outerMethodDesc = outerMethodDesc;
  }

  public static AnonData applyFrom(ClassNode node) {
    if(node.outerClass == null) return NULL_DATA;
    return new AnonData(node.outerClass, node.outerMethod, node.outerMethodDesc);
  }

  @Override
  public String toString() {
    if(this == NULL_DATA) return "No Anon Data";
    return "AnonData {"+ outerClass + ", " + outerMethod + ", " + outerMethodDesc + "}";
  }

  public static String innerToString(InnerClassNode classNode) {
    return "InnerClassNode {" +
        classNode.name + ", " +
        classNode.outerName + ", " +
        classNode.innerName + ", 0b" + Integer.toBinaryString(classNode.access) + "}";
  }

  public void applyTo(ClassNode node) {
    node.outerClass = outerClass;
    node.outerMethod = outerMethod;
    node.outerMethodDesc = outerMethodDesc;
  }
}
