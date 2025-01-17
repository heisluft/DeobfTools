# Extensible Deobfuscation Mappings Library
This libraries aim is to make the creation, parsing, writing and querying as simple as possible
by exposing an immutable Mappings API, a ServiceLoader for parsers and writers of mappings and
a builder for programmatically creating them.

## Features
- Immutable API for Mappings
- Support for both Renaming and Exceptions
- A MappingsBuilder for programmatically creating Mappings
- A Service based Writer and Parser Interface

## Requirements
This library only requires a working Java 8 installation

## How To Use
### Dependency
The jars are deployed at https://heisluft.de/maven/ under the artifact name `de.heisluft.deobf.lib:mappings:{version}`. It is assumed you know how to add maven repo dependencies
with your favourite build tool.

### Creating Mappings
Use a `MappingsBuilder` instance for the manipulation of mappings:
```java
MappingsBuilder b = new MappingsBuilder();
// See Javadoc for all methods
b.addClassMapping("oldClassName", "renamedClassName");
b.addFieldMapping("oldClassName", "oldFieldName", "newFieldName");
b.addClassMapping("oldClassName", "oldMethodName", "oldMethodDescriptor", "newMethodName");
b.addExceptions(Map.of("oldClassName" + "oldMethodName" + "oldMethodDescriptor", Set.of("java/io/IOException")));
Mappings result = b.build();
```
As Mappings are immutable, calling `build()` again after adding another mapping will result in another `Mappings` instance.

### Parsing / Writing Mapping Files
Use the `MappingsHandlers` class for retreiving an implementation of `MappingsHandler` for a
file format, then call its `parseMappings(Path)` and `writeMappings(Path)` methods:
```java
  Path inPath = ..., outPath = ...;
  MappingsHandler handler = MappingsHandlers.findFileHandler(inPath);
  try {
    Mappings mappings = handler.parseMappings(inPath);
   doStuff(mappings);
  } catch(IOException e) {
    // handle
  }
  MappingsBuilder b = new MappingsBuilder();
  b.doStuff();
  try {
    MappingsHandlers.findFileHandler(outPath).writeMappings(b.build());
  } catch(IOException e) {
    // handle
  }
```
The file extension will determine which handler is used, you can also use `findHandler(fileExtension)` directly

**Note:** `findHandler()` and `findFileHandler()` will return `null` if no implementation is available for a given file format

### Default Implementations (always available)
 - Fergie, it handles .frg files.
 - RetroGuardScript, handles .rgs files, does not currently support writing

### Adding an own file format
Extending the set of available formats is easy:
1. Have a class implement `MappingsHandler`, e.g. `MyImplClass` in package `com.myorg`
2. In your `META-INF` dir, make sure you have a `services` subdir
3. Create the file `META-INF/services/de.heisluft.deobf.mappings.MappingsHandler`
4. Write your implementation class name to the file, e.g. `com.myorg.MyImplClass`
