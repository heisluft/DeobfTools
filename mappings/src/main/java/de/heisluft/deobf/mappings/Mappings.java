package de.heisluft.deobf.mappings;

import de.heisluft.deobf.mappings.util.TriConsumer;

import java.util.*;
import java.util.function.BiConsumer;

/**
 * Mappings act as an interface for remappers of all kinds. They store information about renamed
 * methods, class names, and field names as well as lists of exceptions associated with methods.
 * <br>
 * Mappings are considered to be immutable, as there is no API exposing mutable data.
 * To create Mappings from outside the package, use {@link MappingsBuilder} instances.
 */
public class Mappings {

  /**
   * All primitive binary names
   */
  private static final List<String> PRIMITIVES = Arrays.asList("B", "C", "D", "F", "I", "J", "S", "V", "Z");
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
  protected final Map<String, Map<MdMeta, String>> methods = new HashMap<>();
  /**
   * All exceptions and parameters added with the mappings, mapped by className + methodName + methodDesc
   * set of exception class names, list of parameter names. exception class names may or may not be already remapped
   */
  protected final Map<String, Map<MdMeta, MdExtra>> extraData = new HashMap<>();

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
    toClone.fields.forEach((k,v) -> fields.computeIfAbsent(k, _k -> new HashMap<>()).putAll(v));
    toClone.methods.forEach((k,v) -> methods.computeIfAbsent(k, _k -> new HashMap<>()).putAll(v));
    toClone.extraData.forEach((k, v) -> v.forEach((mdMeta, mdExtra) -> extraData.computeIfAbsent(k, _k -> new HashMap<>()).computeIfAbsent(mdMeta, _k -> new MdExtra(mdExtra))));
  }

  public void forAllClasses(BiConsumer<String, String> consumer) {
    classes.forEach(consumer);
  }

  public void forAllFields(TriConsumer<String, String, String> consumer) {
    fields.forEach((s, stringStringMap) -> stringStringMap.forEach((s1, s2) -> consumer.accept(s, s1, s2)));
  }

  public void forAllMethods(TriConsumer<String, MdMeta, String> consumer) {
    methods.forEach((s, mdMetaStringMap) -> mdMetaStringMap.forEach((mdMeta, s1) -> consumer.accept(s, mdMeta, s1)));
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
    return methods.getOrDefault(className, Collections.emptyMap()).get(new MdMeta(methodName, methodDescriptor));
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
    return fields.getOrDefault(className, Collections.emptyMap()).get(fieldName);
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
    return methods.getOrDefault(className, Collections.emptyMap()).containsKey(new MdMeta(methodName, methodDescriptor));
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
    return fields.getOrDefault(className, Collections.emptyMap()).containsKey(fieldName);
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
   * @return a set of all exceptions for this method, never {@code null}
   */
  public Set<String> getExceptions(String className, String methodName, String methodDescriptor) {
    return extraData.getOrDefault(className, Collections.emptyMap()).getOrDefault(new MdMeta(methodName, methodDescriptor), MdExtra.EMPTY).exceptions;
  }

  /**
   * Generates a reversed set of mappings. consider the mappings a->b, this generates b->a.
   * This does not generate reverse parameter mappings or "anti exceptions"
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
      Map<MdMeta, String> reversedNames = new HashMap<>();
      nameMap.forEach((nameDescTuple, renamed) ->
        reversedNames.put(new MdMeta(renamed, remapDescriptor(nameDescTuple.desc)), nameDescTuple.name)
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
    classes.entrySet().stream().filter(e -> e.getKey().equals(e.getValue())).forEach(e -> mappings.classes.put(e.getKey(), e.getValue()));
    fields.forEach((className, map) -> {
      if(map.entrySet().stream().anyMatch(e -> e.getKey().equals(e.getValue()))) return;
      Map<String, String> values = new HashMap<>();
      map.forEach((fieldName, remappedFieldName) -> {
        if(!fieldName.equals(remappedFieldName)) values.put(fieldName, remappedFieldName);
      });
      mappings.fields.put(className, values);
    });
    methods.forEach((className, map) -> {
      if(map.entrySet().stream().allMatch(e -> e.getKey().name.equals(e.getValue()) && !extraData.containsKey(className + e.getKey()))) return;
      Map<MdMeta, String> values = new HashMap<>();
      map.forEach((tuple, remappedMethodName) -> {
        if(!tuple.name.equals(remappedMethodName) || extraData.containsKey(className + tuple.name + tuple.desc)) values.put(tuple, remappedMethodName);
      });
      mappings.methods.put(className, values);
    });
    mappings.extraData.putAll(extraData);
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
      Map<MdMeta, String> values = new HashMap<>();
      methods.get(key).forEach((tuple2, renamedMd) -> {
        String toRenamedMd = other.methods.get(key).get(tuple2);
        if(!renamedMd.equals(toRenamedMd))
          values.put(new MdMeta(renamedMd, remapDescriptor(tuple2.desc)), toRenamedMd);
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
      Map<MdMeta, String> otherNameMap = other.methods.getOrDefault(getClassName(className), new HashMap<>());
      Map<MdMeta, String> resultingNames = new HashMap<>();
      nameMap.forEach((nameDescTuple, renamed) ->
          resultingNames.put(nameDescTuple, otherNameMap.getOrDefault(new MdMeta(renamed, remapDescriptor(nameDescTuple.desc)), renamed))
      );
      mappings.methods.put(className, resultingNames);
    });
    return mappings;
  }

  /**
   * Generates Mappings that join both the entries of this and other. Where entries clash, the entries of these mappings
   * take precedence over the entries of {@code other}. Parameters are overriden, Exceptions are joined
   *
   * @param other the secondary "supplementary" mappings
   * @return the composite mappings
   */
  public Mappings join(Mappings other) {
    Mappings mappings = new Mappings(other);
    mappings.classes.putAll(classes);
    fields.forEach((k,v) -> mappings.fields.computeIfAbsent(k, _k -> new HashMap<>()).putAll(v));
    methods.forEach((k,v) -> mappings.methods.computeIfAbsent(k, _k -> new HashMap<>()).putAll(v));
    extraData.forEach((k, v) -> v.forEach((mdMeta, mdExtra) -> {
      MdExtra extra = extraData.computeIfAbsent(k, _k -> new HashMap<>()).computeIfAbsent(mdMeta, _k -> new MdExtra());
      extra.exceptions.addAll(mdExtra.exceptions);
      extra.parameters.clear();
      extra.parameters.addAll(mdExtra.parameters);
    }));
    return mappings;
  }

  /**
   * Remaps a given descriptor with these mappings
   * @param descriptor the descriptor to remap
   * @return the remapped descriptor
   */
  public String remapDescriptor(String descriptor) {
    StringBuilder result = new StringBuilder();
    //Method descriptors start with '('
    if(descriptor.startsWith("(")) {
      // split String at ')',
      // example descriptor "(J[Ljava/lang/String;S)[I" -> ["(J[Ljava/lang/String;S", "[I"]
      String[] split = descriptor.split("\\)");
      // "(J[Ljava/lang/String;S" -> "J[Ljava/lang/String;S"
      String argsDescriptor = split[0].substring(1);
      if(argsDescriptor.isEmpty()) result.append("()");
      else {
        result.append("(");
        //Parse chars LTR
        PrimitiveIterator.OfInt iterator = argsDescriptor.chars().iterator();
        List<Character> currentName = new ArrayList<>();
        boolean inWord = false;
        while(iterator.hasNext()) {
          char c = (char) iterator.nextInt();
          if(c != 'L' && !inWord) {
            result.append(c);
            //Reference descriptors start with 'L'
          } else if(c == 'L') {
            inWord = true;
            currentName.add(c);
            // ';' marks the end of a reference type descriptor
          } else if(c == ';') {
            currentName.add(c);
            // deobfuscate the finished descriptor and append it
            result.append(remapDescriptor(toString(currentName)));
            currentName.clear();
            inWord = false;
          } else currentName.add(c);
        }
        result.append(')');
      }
      //descriptor becomes the return type descriptor e.g. "(J[Ljava/lang/String;S)[I" -> [I
      descriptor = split[1];
    }
    //Copy descriptor so e.g. simple [I descs can be returned easily
    String cpy = descriptor;
    // strip arrays, count the dimensions for later
    int arrDim = 0;
    while(cpy.startsWith("[")) {
      arrDim++;
      cpy = cpy.substring(1);
    }
    // primitives don't need to be deobfed
    if(PRIMITIVES.contains(cpy)) return result + descriptor;
    // Strip L and ; for lookup (Lmy/package/Class; -> my/package/Class)
    cpy = cpy.substring(1, cpy.length() - 1);
    // the mappings do not contain the class, no deobfuscation needed (e.g. java/lang/String...)
    if(!hasClassMapping(cpy)) return result + descriptor;
    //prepend the array dimensions if any
    for(int i = 0; i < arrDim; i++) result.append('[');
    //convert deobfed class name to descriptor (my/deobfed/ClassName -> Lmy/deobfed/ClassName;)
    return result.append('L').append(getClassName(cpy)).append(';').toString();
  }

  /**
   * Joins the given Collection of characters to a string
   *
   * @param chars
   *     the chars to be joined
   *
   * @return the joined string
   */
  private static String toString(Collection<Character> chars) {
    StringBuilder builder = new StringBuilder();
    chars.forEach(builder::append);
    return builder.toString();
  }
}