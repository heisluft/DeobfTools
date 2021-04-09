package de.heisluft.reveng;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

public class MappingStatus {
  public static void main(String[] args) throws IOException {
    if(args.length != 1) {
      System.out.println("MappingsFile Required!");
      return;
    }
    int renamed = 0;
    List<String> lines = Files.readAllLines(Paths.get(args[0]));
    for(String line: lines) {
      String[] parts = line.split(" ");
      switch(parts[0]) {
        case "CL:":
          if(!parts[1].substring(parts[1].lastIndexOf('/'))
              .equals(parts[2].substring(parts[2].lastIndexOf('/'))) || parts[1].length() > 2) renamed++;
          break;
        case "MD:":
          if(!parts[2].equals(parts[4]) || parts[2].length() > 2) renamed++;
          break;
        case "FD:":
          if(!parts[2].equals(parts[3]) || parts[2].length() > 2 || parts[2].equals("id")) renamed++;
      }
    };
    System.out.println("####Stats:####\nrenamed Entries: " + renamed);
    System.out.println("unrenamed Entries: " + (lines.size() - renamed));
    System.out.println("In Percent: " + (renamed * 100 / lines.size()) + "%");
  }
}
