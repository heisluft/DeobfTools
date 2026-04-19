package de.heisluft.deobf.tooling;

import de.heisluft.cli.simplecli.ArgDefinition;
import de.heisluft.cli.simplecli.Command;
import de.heisluft.cli.simplecli.OptionDefinition;
import de.heisluft.cli.simplecli.OptionParseResult;
import de.heisluft.cli.simplecli.OptionParser;
import de.heisluft.deobf.mappings.Mappings;
import de.heisluft.deobf.mappings.MappingsHandler;
import de.heisluft.deobf.mappings.MappingsHandlers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static de.heisluft.cli.simplecli.OptionDefinition.flag;
import static de.heisluft.cli.simplecli.OptionDefinition.valued;
import static de.heisluft.cli.simplecli.OptionParser.ROOT_COMMAND;
import static de.heisluft.cli.simplecli.OptionParser.eachOf;
import static de.heisluft.cli.simplecli.ValidationResult.invalid;
import static de.heisluft.cli.simplecli.ValidationResult.valid;

public class Main {

  private static void displayHelpAndExit(OptionParser p) {
    System.out.println(p.formatHelp("Heislufts Reverse Engineering Suite version 0.2\nusage: DeobfTools [options] <task> <input> <mappings>", 100));
    System.exit(0);
  }

  public static void main(String[] args) throws IOException {
    List<String> ignoredPaths = new ArrayList<>();
    AtomicReference<Mappings> supplementaryMappings = new AtomicReference<>();
    ArgDefinition<Path> outPath = ArgDefinition.arg("outputPath", Path.class).validatedBy(p -> Files.exists(p) && !Files.isWritable(p) ? invalid("output path is not writable") : valid()).build();
    OptionDefinition<Path> jdkPath = valued("jdk", Path.class)
        .description("Valid only for 'map' and 'writeFRG2'. Path to JDK, used for inferring exceptions", "jdkPath")
        .validatedBy(p -> !Files.isDirectory(p) ? invalid("jdk path does not point to a directory") : valid())
        .build();
    OptionDefinition<Void> noBridgeStrip = flag("noBridgeStrip")
        .shorthand('b')
        .description("Valid only for 'remap'. Skips stripping of bridge and synthetic access modifiers for bridge methods.")
        .build();
    OptionDefinition<Void> explicitExceptions = flag("explicitExceptions")
        .shorthand('x')
        .description("Valid only for 'remap'. If set, exceptions for a method don't automatically propagate downwards. Requires explicitly added exceptions within mappings.")
        .build();
    OptionDefinition<Void> regenerateFieldDescriptors = flag("regenerateFieldDescriptors")
        .description("Valid only for 'map' and 'writeFRG2'. If set and supplementaryMappings are supplied, it will regenerate field mappings with the current jars field descriptors. Useful for converting frg to frg2 mappings.")
        .build();
    OptionDefinition<Void> recomputeExceptionData = flag("recomputeExceptionData")
        .shorthand('e')
        .description("Valid only for 'map' and 'writeFRG2'. If set exception data is not reused from old mappings and is instead recomputed.")
        .build();
    OptionParser parser = new OptionParser(
        new Command("map", "Generates obfuscation mappings from the <input> jar and writes them to <mappings>."),
        new Command("remap", "Remaps the <input> jar with the specified <mappings> file and writes it to <output>."),
        new Command("genReverseMappings", "Generates reverse obfuscation mappings from the <input> mappings and writes them to <mappings>."),
        new Command("genMediatorMappings", "Writes mappings mapping the output of <input> to the output of <mappings> to <output>."),
        new Command("genConversionMappings", "Writes mappings mapping the input of <input> to the output of <mappings> to <output>."),
        new Command("cleanMappings", "Writes a clean version of the mappings at <input> to <mapping>."),
        new Command("writeFRG2", "Parses <mappings> and emits corresponding FRG2 mappings to <output>. Needs the file mapped by <mappings> as <input> for computing exception data and field descriptors.")
    );
    parser.addOptions(eachOf("map", "remap", "writeFRG2"), valued("ignorepaths")
        .description("A List of paths to ignore from the input jar. Multiple Paths are separated using ; (semicolon). These Paths are treated as wildcards. For example, -i com;org/unwanted/ would lead the program to exclude all paths starting with either 'com' or 'org/unwanted/' eg. 'com/i.class', 'computer.xml', 'org/unwanted/b.gif'. This option will be ignored for tasks only operating on mappings", "pathsToIgnore")
        .callback(s -> ignoredPaths.addAll(Arrays.asList(s.split(";"))))
        .build()
    );
    parser.addOptions(eachOf("remap"), noBridgeStrip, explicitExceptions);
    parser.addOptions(eachOf("writeFRG2"), regenerateFieldDescriptors, recomputeExceptionData, jdkPath);
    parser.addOptions(eachOf("map"),
        valued("supplementary", Path.class)
            .description("Valid only for 'map'. Provides supplementary mappings. For these, no new mappings will be generated, instead they will directly be merged into the output mappings file. ", "mappingsPath")
            .callback(p -> {
              if(!Files.isReadable(p)) throw new IllegalArgumentException("mappings path does not exist or is not readable");
              try {
                supplementaryMappings.set(MappingsHandlers.parseMappings(p));
              } catch(IOException exception) {
                throw new IllegalArgumentException("Error reading mappings at " + p, exception);
              }
            })
            .build(),
        regenerateFieldDescriptors,
        recomputeExceptionData
    );

    parser.addOptions(ROOT_COMMAND, flag("help")
        .description("Displays this message.")
        .whenSet(() -> displayHelpAndExit(parser))
        .build()
    );

    var inArg = ArgDefinition.arg("inputPath", Path.class)
        .validatedBy(value -> Files.isRegularFile(value) && Files.isReadable(value) ? valid() : invalid("Input path is not a file or unreadable."))
        .build();
    var mappingsArg = ArgDefinition.arg("mappingsPath", Path.class).build();
    parser.addRequiredArgs(Predicate.not(ROOT_COMMAND), inArg, mappingsArg);
    parser.addRequiredArgs(eachOf("remap", "genConversionMappings", "genMediatorMappings", "writeFRG2"), outPath);
    OptionParseResult result = parser.parse(args);
    if(result.subcommand == null)  {
      displayHelpAndExit(parser);
      return;
    }

    Path inputPath = result.getArg(inArg);
    Path mappingsPath = result.getArg(mappingsArg);

    try {
      MappingsHandler fallback = MappingsHandlers.findHandler("frg");
      MappingsHandler iHandler = MappingsHandlers.findFileHandler(inputPath.toString());
      MappingsHandler mHandler = MappingsHandlers.findFileHandler(mappingsPath.toString());
      switch(result.subcommand) {
        case "cleanMappings":
          mHandler.writeMappings(mHandler.parseMappings(inputPath).clean(), mappingsPath);
          break;
        case "genMediatorMappings":
          Mappings a2b = iHandler.parseMappings(inputPath);
          Mappings a2c = mHandler.parseMappings(mappingsPath);
          MappingsHandler oHandler = MappingsHandlers.findFileHandler(result.getArg(outPath).toString());
          (oHandler != null ? oHandler : fallback).writeMappings(a2b.generateMediatorMappings(a2c), result.getArg(outPath));
          break;
        case "genConversionMappings":
          a2b = iHandler.parseMappings(inputPath);
          Mappings b2c = mHandler.parseMappings(mappingsPath);
          oHandler = MappingsHandlers.findFileHandler(result.getArg(outPath).toString());
          (oHandler != null ? oHandler : fallback).writeMappings(a2b.generateConversionMethods(b2c), result.getArg(outPath));
          break;
        case "remap":
          if(result.getArg(outPath).equals(inputPath)) {
            System.out.println("The output path must not match the input path.");
            return;
          }
          new Remapper().remapJar(inputPath, mHandler.parseMappings(mappingsPath), result.getArg(outPath), ignoredPaths, !result.isSet(noBridgeStrip), result.isSet(explicitExceptions));
          break;
        case "genReverseMappings":
          mHandler.writeMappings(mHandler.parseMappings(inputPath).generateReverseMappings(), mappingsPath);
          break;
        case "writeFRG2":
          oHandler = MappingsHandlers.findFileHandler(result.getArg(outPath).toString());
          oHandler.writeMappings(new MappingsGenerator(
              mHandler.parseMappings(mappingsPath),
              result.isSet(jdkPath) ? new JDKClassProvider(result.getOption(jdkPath)) : new JDKClassProvider()
          ).generateMappings(inputPath, ignoredPaths, result.isSet(regenerateFieldDescriptors), result.isSet(recomputeExceptionData), true), result.getArg(outPath));
          break;
        default:
          mHandler.writeMappings(new MappingsGenerator(
              supplementaryMappings.get(),
              result.isSet(jdkPath) ? new JDKClassProvider(result.getOption(jdkPath)) : new JDKClassProvider()
          ).generateMappings(inputPath, ignoredPaths, result.isSet(regenerateFieldDescriptors), result.isSet(recomputeExceptionData), false), mappingsPath);
          break;
      }
    } catch(IOException e) {
      e.printStackTrace();
    }
  }
}
