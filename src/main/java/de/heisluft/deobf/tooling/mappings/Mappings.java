package de.heisluft.deobf.tooling.mappings;

import de.heisluft.deobf.tooling.Remapper;
import de.heisluft.function.Tuple2;
import de.heisluft.stream.BiStream;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Mappings act as an interface for remappers of all kinds. They store information about renamed
 * methods, class names, and field names as well as lists of exceptions associated with methods.
 * <br>
 * Mappings are considered to be immutable, as there is no API exposing mutable data.
 * To create Mappings from outside the package, use {@link MappingsBuilder} instances.
 */
public class Mappings {
  /**
   * All Class mappings, names are jvm names ('/' as delimiter) mapped as follows: classMame ->
   * remappedClassName
   */
  protected final Map<String, String> classes = new HashMap<>();
  /**
   * All field mappings mapped as follows: className -> fieldName -> remappedName
   */
  protected final Map<String, Map<String, String>> fields = new HashMap<>();
  /**
   * All method mappings mapped as follows: className -> (methodName + methodDesc) -> remappedName
   */
  protected final Map<String, Map<Tuple2<String, String>, String>> methods = new HashMap<>();
  /**
   * All exceptions added with the mappings, mapped as follows: className + methodName + methodDesc
   * -> list of exception class names exception class names may or may not be already remapped
   */
  protected final Map<String, Set<String>> exceptions = new HashMap<>();

  /**
   * Mappings are not to be instantiated outside the Package, use {@link MappingsBuilder#build()}
   */
  protected Mappings() {}

  /**
   * Clone the given mappings. Values are deep-cloned, as the backing Maps are often mutable.
   *
   * @param toClone the mappings t
   */
  protected Mappings(Mappings toClone) {
    classes.putAll(toClone.classes);
    toClone.fields.forEach((k,v) -> {
      fields.put(k, new HashMap<>());
      fields.get(k).putAll(v);
    });
    toClone.methods.forEach((k,v) -> {
      methods.put(k, new HashMap<>());
      methods.get(k).putAll(v);
    });
    toClone.exceptions.forEach((k,v) -> {
      exceptions.put(k, new HashSet<>());
      exceptions.get(k).addAll(v);
    });
  }

  /**
   * Retrieves a mapped name for a given class, giving back the className as fallback Use in
   * conjunction with {@link Mappings#hasClassMapping(String)}
   *
   * @param className
   *     the classes name
   *
   * @return the mapped name or className if not found
   *
   * @see Mappings#hasClassMapping(String)
   */
  public String getClassName(String className) {
    return classes.getOrDefault(className, className);
  }

  /**
   * Retrieves a mapped name for a given method.
   *
   * @param className
   *     the name of the class containing the method
   * @param methodName
   *     The methods name
   * @param methodDescriptor
   *     The methods descriptor
   *
   * @return the mapped name or {@code null} if not found
   */
  public String getMethodName(String className, String methodName, String methodDescriptor) {
    return methods.getOrDefault(className, new HashMap<>()).get(new Tuple2<>(methodName, methodDescriptor));
  }

  /**
   * Retrieves a mapped name for a given field.
   *
   * @param className
   *     the name of the class containing the field
   * @param fieldName
   *     The fields name
   *
   * @return the mapped name or {@code null} if not found
   */
  public String getFieldName(String className, String fieldName) {
    return fields.getOrDefault(className, new HashMap<>()).get(fieldName);
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
    return classes.containsKey(className);
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
    return methods.getOrDefault(className, new HashMap<>()).containsKey(new Tuple2<>(methodName, methodDescriptor));
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
    return fields.getOrDefault(className, new HashMap<>()).containsKey(fieldName);
  }

  /**
   * Retrieves a list of all exceptions associated with a given Method. No guarantee is made whether
   * these names are obfuscated or not.
   *
   * @param className
   *     the name of the class containing the method
   * @param methodName
   *     The methods name
   * @param methodDescriptor
   *     The methods descriptor
   *
   * @return a list of all exceptions for this method, never {@code null}
   */
  public Set<String> getExceptions(String className, String methodName, String methodDescriptor) {
    return exceptions.getOrDefault(className + methodName + methodDescriptor, new HashSet<>());
  }

  /**
   * Generates a reversed set of mappings. consider the mappings a->b, this generates b->a
   *
   * @return the reversed (b->a) mappings
   */
  public Mappings generateReverseMappings() {
    Mappings mappings = new Mappings();
    classes.forEach((name, renamed) -> mappings.classes.put(renamed, name));
    fields.forEach((className, nameMap) -> {
      Map<String, String> reversedNames = new HashMap<>();
      nameMap.forEach((name, renamed) -> reversedNames.put(renamed, name));
      mappings.fields.put(getClassName(className), reversedNames);
    });
    methods.forEach((className, nameMap) -> {
      Map<Tuple2<String, String>, String> reversedNames = new HashMap<>();
      nameMap.forEach((nameDescTuple, renamed) ->
        reversedNames.put(new Tuple2<>(renamed, Remapper.INSTANCE.remapDescriptor(nameDescTuple._2, this)), nameDescTuple._1)
      );
      mappings.methods.put(getClassName(className), reversedNames);
    });
    return mappings;
  }

  /**
   * Cleans up the mappings, removing all entries mapping to themselves
   *
   * @return the cleaned up mappings
   */
  public Mappings clean() {
    Mappings mappings = new Mappings();
    mappings.classes.putAll(BiStream.streamMap(classes).filter(String::equals).toMap());
    fields.forEach((className, map) -> {
      if(BiStream.streamMap(map).allMatch(String::equals)) return;
      Map<String, String> values = new HashMap<>();
      map.forEach((fieldName, remappedFieldName) -> {
        if(!fieldName.equals(remappedFieldName)) values.put(fieldName, remappedFieldName);
      });
      mappings.fields.put(className, values);
    });
    methods.forEach((className, map) -> {
      if(BiStream.streamMap(map).allMatch((tup, s) -> tup._1.equals(s) && !exceptions.containsKey(className + tup._1 + tup._2))) return;
      Map<Tuple2<String, String>, String> values = new HashMap<>();
      map.forEach((tuple, remappedMethodName) -> {
        if(!tuple._1.equals(remappedMethodName) || exceptions.containsKey(className + tuple._1 + tuple._2)) values.put(tuple, remappedMethodName);
      });
      mappings.methods.put(className, values);
    });
    mappings.exceptions.putAll(exceptions);
    return mappings;
  }

  /**
   * Generates Mappings Mediating between these mappings and other.
   * Consider two Mappings a->b and a->c, the returned mappings represent b->c
   *
   * @param other
   *     the mappings to convert to (the a->c mappings)
   *
   * @return the resulting (b->c) mappings
   */
  public Mappings generateMediatorMappings(Mappings other) {
    Mappings mappings = new Mappings();
    classes.keySet().forEach(key -> {
      if(!classes.get(key).equals(other.classes.get(key)))
        mappings.classes.put(classes.get(key), other.classes.get(key));
    });
    fields.keySet().forEach(key -> {
      if(!other.fields.containsKey(key)) return;
      Map<String, String> values = new HashMap<>();
      fields.get(key).forEach((name, renamedFd) -> {
        String toRenamedFd = other.fields.get(key).get(name);
        if(!renamedFd.equals(toRenamedFd))
          values.put(renamedFd, toRenamedFd);
      });
      mappings.fields.put(getClassName(key), values);
    });
    methods.keySet().forEach(key -> {
      if(!other.methods.containsKey(key)) return;
      Map<Tuple2<String, String>, String> values = new HashMap<>();
      methods.get(key).forEach((tuple2, renamedMd) -> {
        String toRenamedMd = other.methods.get(key).get(tuple2);
        if(!renamedMd.equals(toRenamedMd))
          values.put(tuple2.map1(b -> renamedMd).map2(desc -> Remapper.INSTANCE.remapDescriptor(desc, this)), toRenamedMd);
      });
      mappings.methods.put(getClassName(key), values);
    });
    return mappings;
  }
  /**
   * Generates Mappings Converting between these mappings and other.
   * Consider two Mappings a->b and b->c, the returned mappings represent a->c
   *
   * @param other
   *     the mappings to convert to (the b->c mappings)
   *
   * @return the resulting (a->c) mappings
   */
  public Mappings generateConversionMethods(Mappings other) {
    Mappings mappings = new Mappings();
    classes.forEach((name, renamed) -> mappings.classes.put(name, other.getClassName(renamed)));
    fields.forEach((className, nameMap) -> {
      Map<String, String> otherNameMap = other.fields.getOrDefault(getClassName(className), new HashMap<>());
      Map<String, String> resultingNames = new HashMap<>();
      nameMap.forEach((name, renamed) -> resultingNames.put(name, otherNameMap.getOrDefault(renamed, renamed)));
      mappings.fields.put(className, resultingNames);
    });
    methods.forEach((className, nameMap) -> {
      Map<Tuple2<String, String>, String> otherNameMap = other.methods.getOrDefault(getClassName(className), new HashMap<>());
      Map<Tuple2<String, String>, String> resultingNames = new HashMap<>();
      nameMap.forEach((nameDescTuple, renamed) ->
          resultingNames.put(nameDescTuple, otherNameMap.getOrDefault(new Tuple2<>(renamed, Remapper.INSTANCE.remapDescriptor(nameDescTuple._2, this)), renamed))
      );
      mappings.methods.put(className, resultingNames);
    });
    return mappings;
  }

  /**
   * Generates Mappings that join both the entries of this and other. Where entries clash, the entries of these mappings
   * take precedence over the entries of {@code other}.
   *
   * @param other the secondary "supplementary" mappings
   * @return the composite mappings
   */
  public Mappings join(Mappings other) {
    Mappings mappings = new Mappings(other);
    mappings.classes.putAll(classes);
    fields.forEach((k,v) -> {
      if(!mappings.fields.containsKey(k)) mappings.fields.put(k, new HashMap<>());
      mappings.fields.get(k).putAll(v);
    });
    methods.forEach((k,v) -> {
      if(!mappings.methods.containsKey(k)) mappings.methods.put(k, new HashMap<>());
      mappings.methods.get(k).putAll(v);
    });
    exceptions.forEach((k,v) -> {
      if(!mappings.exceptions.containsKey(k)) mappings.exceptions.put(k, new HashSet<>());
      mappings.exceptions.get(k).addAll(v);
    });
    return mappings;
  }
}