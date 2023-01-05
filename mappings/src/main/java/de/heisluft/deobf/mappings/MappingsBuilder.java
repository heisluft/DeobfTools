package de.heisluft.deobf.mappings;

import java.util.*;

/**
 * MappingsBuilder provides an interface for composing new Mappings without compromising the
 * API Immutability of Mappings
 */
public final class MappingsBuilder {

  /**
   * The mappings populated by this builder. These are mutable, so we don't expose them.
   */
  private final Mappings mappings;

  public MappingsBuilder(Mappings mappings) {
    this.mappings = new Mappings(mappings);
  }

  public MappingsBuilder() {
    this.mappings = new Mappings();
  }

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
   * @param rName the remapped name
   */
  public void addMethodMapping(String cName, String mName, String mDesc, String rName) {
    if(!mappings.methods.containsKey(cName)) mappings.methods.put(cName, new HashMap<>());
    mappings.methods.get(cName).put(new MdMeta(mName, mDesc), rName);
  }

  /**
   * Adds all exceptions to the mappings. Exceptions will be appended instead of overridden.
   *
   * @param exceptions the list of exceptions to add
   */
  public void addExceptions(Map<String, List<String>> exceptions) {
    exceptions.forEach((s, strings) -> {
      int dot = s.indexOf('.'), lPar = s.indexOf('(');
      mappings.extraData.computeIfAbsent(s.substring(0, dot), t -> new HashMap<>())
          .computeIfAbsent(new MdMeta(s.substring(dot + 1, lPar), s.substring(lPar)), _k -> new MdExtra())
          .exceptions.addAll(strings);
    });
  }

  /**
   * Sets the parameter mappings for a given method. Previous Mappings are overridden.
   * @param className the binary name of the containing class
   * @param methodName the method name
   * @param methodDesc the methods descriptor
   * @param parameterNames the list of parameter names to set
   */
  public void setParameters(String className, String methodName, String methodDesc, List<String> parameterNames) {
    List<String> params = mappings.extraData.computeIfAbsent(className, _k -> new HashMap<>()).computeIfAbsent(new MdMeta(methodName, methodDesc), _k -> new MdExtra()).parameters;
    params.clear();
    params.addAll(parameterNames);
  }

  /**
   * Adds exceptions for the given method to the mappings. Exceptions will be appended instead of overridden.
   *
   * @param className the binary name of the containing class
   * @param methodName the method name
   * @param methodDesc the methods descriptor
   * @param exceptions the list of exceptions to add
   */
  public void addExceptions(String className, String methodName, String methodDesc, Collection<String> exceptions) {
    mappings.extraData.computeIfAbsent(className, _k -> new HashMap<>()).computeIfAbsent(new MdMeta(methodName, methodDesc), _k -> new MdExtra()).exceptions.addAll(exceptions);
  }

  /**
   * Returns if any exceptions are mapped for a given method
   * @param cName
   *     the name of the class declaring the method
   * @param mName
   *     the name of the method
   * @param mDesc
   *     the descriptor of the method
   * @return true if there are any exceptions for the method, false otherwise
   */
  public boolean hasExceptionsFor(String cName, String mName, String mDesc) {
    return !mappings.extraData.getOrDefault(cName, Collections.emptyMap()).getOrDefault(new MdMeta(mName, mDesc), MdExtra.EMPTY).exceptions.isEmpty();
  }

  /**
   * Returns if the mappings contain a mapping for a specific class name
   *
   * @param className
   *     the class name to test for
   *
   * @return true if there is a mapping for {@code className}, false otherwise
   */
  public boolean hasClassMapping(String className) {
    return mappings.classes.containsKey(className);
  }

  /**
   * Returns if the mappings contain a mapping for a specific method.
   *
   * @param className
   *     the name of the class declaring the method
   * @param methodName
   *     the name of the method
   * @param methodDescriptor
   *     the descriptor of the method
   *
   * @return true if there is a mapping for the method, false otherwise
   */
  public boolean hasMethodMapping(String className, String methodName, String methodDescriptor) {
    return mappings.methods.getOrDefault(className, new HashMap<>()).containsKey(new MdMeta(methodName, methodDescriptor));
  }

  /**
   * Returns if the mappings contain a mapping for a specific field
   *
   * @param className
   *     the name of the class declaring the field
   * @param fieldName
   *     the name of the field
   *
   * @return true if there is a mapping for {@code className}, false otherwise
   */
  public boolean hasFieldMapping(String className, String fieldName) {
    return mappings.fields.getOrDefault(className, new HashMap<>()).containsKey(fieldName);
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
