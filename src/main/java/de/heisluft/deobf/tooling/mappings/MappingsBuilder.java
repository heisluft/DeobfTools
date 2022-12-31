package de.heisluft.deobf.tooling.mappings;

import de.heisluft.function.Tuple2;

import java.util.HashMap;

/**
 * MappingsBuilder provides an interface for composing new Mappings without compromising the
 * API Immutability of Mappings
 */
public final class MappingsBuilder {

  /**
   * The mappings populated by this builder. These are mutable, so we don't expose them.
   */
  private final Mappings mappings = new Mappings();

  /**
   * Builds the final Mappings. The returned Mappings are immutable as required in their JavaDoc, so adding new mappings
   * after building will not change their state. This also means that in the following scenario
   * <pre>
   *  MappingsBuilder b = ...;
   *  Mappings a = b.build();
   *  b.someMutatingMethod();
   *  boolean c = a.equals(b.build());
   * </pre>
   * {@code c} will be {@code false}.
   *
   * @return the built mappings
   */
  public Mappings build() {
    return new Mappings(mappings);
  }

  /**
   * Add a class mapping. Existing mappings will be overridden.
   * @param cName the binary class name to map
   * @param rName the remapped name
   */
  public void addClassMapping(String cName, String rName) {
    mappings.classes.put(cName, rName);
  }

  /**
   * Add a field mapping. Existing mappings will be overridden.
   * @param cName the binary name of the containing class
   * @param fName the field name to map
   * @param rName the remapped name
   */
  public void addFieldMapping(String cName, String fName, String rName) {
    if(!mappings.fields.containsKey(cName)) mappings.fields.put(cName, new HashMap<>());
    mappings.fields.get(cName).put(fName, rName);
  }

  /**
   * Add a method mapping. Existing mappings will be overridden.
   * @param cName the binary name of the containing class
   * @param mName the method name to map
   * @param mDesc the methods descriptor
   * @param rName the remappexd name
   */
  public void addMethodMapping(String cName, String mName, String mDesc, String rName) {
    if(!mappings.methods.containsKey(cName)) mappings.methods.put(cName, new HashMap<>());
    mappings.methods.get(cName).put(new Tuple2<>(mName, mDesc), rName);
  }

  /**
   * Returns whether any class mapping has lookFor as remapped name
   *
   * @param lookFor the binary class name to look for
   * @return whether the target is already mapped to
   */
  public boolean hasClassNameTarget(String lookFor) {
    return mappings.classes.containsValue(lookFor);
  }
}
