package com.inventory.app.boundaries;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Enforces the module boundaries written in {@code module-boundaries.yml} at the repository root.
 *
 * <p>Rules (docs/architecture/module-boundaries.md):
 *
 * <ul>
 *   <li>DEPENDS: a module uses another module only if {@code dependsOn} lists it.
 *   <li>NOT_API: a module uses another (non-library) module only through its {@code api} package.
 *   <li>API_KIND / API_IMPURE: an {@code api} package holds only interfaces, records and enums, and
 *       does not depend on Spring, Mongo or its own module's private packages.
 *   <li>PORT_LOCATION: an interface implemented by another module lives in {@code api.port}.
 *   <li>OPTIONAL_DEPENDENCY: no {@code @Autowired(required = false)} on another module's type.
 *   <li>COLLECTION_*: every collection has one owner; only the owner names or maps it.
 * </ul>
 *
 * <p>Violations that exist today are listed in {@code boundaries/baseline.txt}. The test fails on
 * any violation not in the baseline, and on any baseline line that is no longer a violation (so
 * the list can only shrink). Regenerate it with {@code -Dboundaries.writeBaseline=true}.
 */
class ModuleBoundariesTest {

  private static final String ROOT_PACKAGE = "com.inventory.";
  private static final String BASELINE = "app/src/test/resources/boundaries/baseline.txt";
  private static final String NOT_A_COLLECTION_MARKER = "boundaries:not-a-collection";
  private static final Set<String> FORBIDDEN_IN_API =
      Set.of("org.springframework.", "org.bson.", "com.mongodb.", "lombok.");
  private static final Pattern STRING_CONSTANT =
      Pattern.compile("static\\s+final\\s+String\\s+\\w+\\s*=\\s*\"([a-z_]+)\"");
  private static final Pattern MONGO_CALL = Pattern.compile(
      "mongoTemplate|MongoTemplate|getCollection\\(|indexOps\\(|Aggregation|lookup\\(|\\$lookup|MongoCollection");

  private static Path repoRoot;
  private static Config config;
  private static JavaClasses classes;

  @BeforeAll
  static void load() throws IOException {
    repoRoot = findRepoRoot();
    config = Config.read(repoRoot.resolve("module-boundaries.yml"));
    // Main code only. Other modules arrive as target/classes (reactor) or jars, so jars stay in.
    classes = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.inventory");
  }

  @Test
  void configMatchesThePomFiles() throws IOException {
    Map<String, Set<String>> pomEdges = readPomEdges(repoRoot);
    assertEquals(new TreeSet<>(pomEdges.keySet()), new TreeSet<>(config.dependsOn.keySet()),
        "every module directory needs an entry in module-boundaries.yml, and only those");
    List<String> mismatches = new ArrayList<>();
    pomEdges.forEach((module, deps) -> {
      if (!deps.equals(config.dependsOn.get(module))) {
        mismatches.add(module + ": pom.xml " + new TreeSet<>(deps) + " vs module-boundaries.yml "
            + new TreeSet<>(config.dependsOn.get(module)));
      }
    });
    assertTrue(mismatches.isEmpty(), "dependsOn must mirror pom.xml:\n" + String.join("\n", mismatches));
  }

  @Test
  void everyClassBelongsToAKnownModule() {
    Set<String> unknown = classes.stream()
        .map(JavaClass::getName)
        .filter(n -> !config.dependsOn.containsKey(moduleOf(n)))
        .collect(Collectors.toCollection(TreeSet::new));
    assertTrue(unknown.isEmpty(), "classes outside any module in module-boundaries.yml: " + unknown);
  }

  @Test
  void noNewBoundaryViolations() throws IOException {
    Set<String> found = new TreeSet<>();
    found.addAll(dependencyViolations());
    found.addAll(apiPackageViolations());
    found.addAll(portViolations());
    found.addAll(optionalDependencyViolations());
    found.addAll(collectionMappingViolations());
    found.addAll(collectionNameViolations());

    Path baselineFile = repoRoot.resolve(BASELINE);
    if (Boolean.getBoolean("boundaries.writeBaseline")) {
      Files.createDirectories(baselineFile.getParent());
      List<String> out = new ArrayList<>();
      out.add("# Module boundary violations that existed when the check was introduced.");
      out.add("# Fixing one means deleting its line here in the same change. Never add lines by hand.");
      out.add("# Regenerate: mvn -pl app -am test -Dtest=ModuleBoundariesTest "
          + "-Dsurefire.failIfNoSpecifiedTests=false -Dboundaries.writeBaseline=true");
      out.addAll(found);
      Files.write(baselineFile, out, StandardCharsets.UTF_8);
      return;
    }

    Set<String> allowed = readBaseline(baselineFile);
    Set<String> added = new TreeSet<>(found);
    added.removeAll(allowed);
    Set<String> fixed = new TreeSet<>(allowed);
    fixed.removeAll(found);

    StringBuilder msg = new StringBuilder();
    if (!added.isEmpty()) {
      msg.append("New module boundary violations (see docs/architecture/module-boundaries.md):\n");
      added.forEach(v -> msg.append("  ").append(v).append('\n'));
      msg.append("Use the other module's api package, or change module-boundaries.yml with review.\n");
    }
    if (!fixed.isEmpty()) {
      msg.append("Fixed violations still listed in ").append(BASELINE).append(" — delete these lines:\n");
      fixed.forEach(v -> msg.append("  ").append(v).append('\n'));
    }
    if (msg.length() > 0) {
      fail(msg.toString());
    }
  }

  // ---- rules ----------------------------------------------------------------------------------

  /** DEPENDS and NOT_API, one line per (origin class, target class) pair. */
  private Set<String> dependencyViolations() {
    Set<String> out = new TreeSet<>();
    for (JavaClass origin : classes) {
      String from = moduleOf(origin.getName());
      for (Dependency d : origin.getDirectDependenciesFromSelf()) {
        JavaClass target = d.getTargetClass().getBaseComponentType();
        String to = moduleOf(target.getName());
        if (to == null || to.equals(from)) {
          continue;
        }
        String pair = topLevel(origin.getName()) + " -> " + topLevel(target.getName());
        if (!config.libraries.contains(to) && !config.dependsOn.getOrDefault(from, Set.of()).contains(to)) {
          out.add("DEPENDS " + from + " -> " + to + " : " + pair);
        } else if (!config.libraries.contains(to) && !isApi(target, to) && !isCompositionRoot(origin)) {
          out.add("NOT_API " + from + " -> " + to + " : " + pair);
        }
      }
    }
    return out;
  }

  /** API_KIND and API_IMPURE for every class inside a module's own api package. */
  private Set<String> apiPackageViolations() {
    Set<String> out = new TreeSet<>();
    for (JavaClass c : classes) {
      String module = moduleOf(c.getName());
      if (config.libraries.contains(module) || !isApi(c, module)) {
        continue;
      }
      boolean lombokBuilder = c.getEnclosingClass().isPresent() && c.getSimpleName().endsWith("Builder");
      if (!(c.isInterface() || c.isEnum() || c.isAnnotation() || isRecord(c) || lombokBuilder)) {
        out.add("API_KIND " + module + " : " + topLevel(c.getName()) + " must be an interface, record or enum");
      }
      for (Dependency d : c.getDirectDependenciesFromSelf()) {
        String t = d.getTargetClass().getBaseComponentType().getName();
        boolean framework = FORBIDDEN_IN_API.stream().anyMatch(t::startsWith);
        boolean ownPrivate = module.equals(moduleOf(t)) && !isApi(d.getTargetClass().getBaseComponentType(), module);
        if (framework || ownPrivate) {
          out.add("API_IMPURE " + module + " : " + topLevel(c.getName()) + " -> " + topLevel(t));
        }
      }
    }
    return out;
  }

  /** PORT_LOCATION: an interface of module X implemented in module Y must sit in X.api.port. */
  private Set<String> portViolations() {
    Set<String> out = new TreeSet<>();
    for (JavaClass impl : classes) {
      String implModule = moduleOf(impl.getName());
      for (JavaClass iface : impl.getRawInterfaces()) {
        String owner = moduleOf(iface.getName());
        if (owner == null || owner.equals(implModule) || config.libraries.contains(owner)) {
          continue;
        }
        if (!iface.getPackageName().startsWith(apiPackage(owner) + ".port")) {
          out.add("PORT_LOCATION " + owner + " <- " + implModule + " : " + topLevel(impl.getName())
              + " implements " + topLevel(iface.getName()));
        }
      }
    }
    return out;
  }

  /** OPTIONAL_DEPENDENCY: a missing bean of another module must fail startup, not be skipped. */
  private Set<String> optionalDependencyViolations() {
    Set<String> out = new TreeSet<>();
    for (JavaClass c : classes) {
      String module = moduleOf(c.getName());
      for (JavaField f : c.getFields()) {
        Optional<JavaAnnotation<JavaField>> autowired =
            f.tryGetAnnotationOfType("org.springframework.beans.factory.annotation.Autowired");
        boolean optional = autowired.flatMap(a -> a.get("required")).map(Boolean.FALSE::equals).orElse(false);
        String typeModule = moduleOf(f.getRawType().getName());
        if (optional && typeModule != null && !typeModule.equals(module) && !config.libraries.contains(typeModule)) {
          out.add("OPTIONAL_DEPENDENCY " + module + " -> " + typeModule + " : " + topLevel(c.getName())
              + "." + f.getName() + " (" + topLevel(f.getRawType().getName()) + ")");
        }
      }
    }
    return out;
  }

  /** Every @Document collection is listed once, under the module that declares the class. */
  private Set<String> collectionMappingViolations() {
    Set<String> out = new TreeSet<>();
    Set<String> mapped = new TreeSet<>();
    for (JavaClass c : classes) {
      Optional<JavaAnnotation<JavaClass>> doc =
          c.tryGetAnnotationOfType("org.springframework.data.mongodb.core.mapping.Document");
      if (doc.isEmpty()) {
        continue;
      }
      String name = collectionName(c, doc.get());
      mapped.add(name);
      String module = moduleOf(c.getName());
      String owner = config.ownerOf.get(name);
      if (owner == null) {
        out.add("COLLECTION_UNLISTED " + module + " : " + topLevel(c.getName()) + " maps " + name);
      } else if (!owner.equals(module)) {
        out.add("COLLECTION_OWNER " + module + " : " + topLevel(c.getName()) + " maps " + name
            + " owned by " + owner);
      }
    }
    for (String listed : config.ownerOf.keySet()) {
      if (!mapped.contains(listed) && !config.unmappedCollections.contains(listed)) {
        out.add("COLLECTION_STALE " + config.ownerOf.get(listed) + " : " + listed
            + " is listed but no @Document maps it");
      }
    }
    return out;
  }

  /**
   * COLLECTION_NAME: a module names another module's collection in source (string constants or
   * Mongo calls), which bypasses the owner. Bytecode does not show string use, so this reads
   * source files. A line that only looks like a collection name carries the marker comment.
   */
  private Set<String> collectionNameViolations() throws IOException {
    Set<String> out = new TreeSet<>();
    for (Path file : mainSourceFiles()) {
      String module = moduleOfSource(file);
      if (module == null) {
        continue;
      }
      String rel = repoRoot.relativize(file).toString();
      for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        String code = line.strip();
        if (code.startsWith("*") || code.startsWith("/*") || code.startsWith("//")
            || line.contains(NOT_A_COLLECTION_MARKER)) {
          continue;
        }
        Matcher constant = STRING_CONSTANT.matcher(line);
        Set<String> names = new TreeSet<>();
        if (constant.find()) {
          names.add(constant.group(1));
        }
        if (MONGO_CALL.matcher(line).find()) {
          Matcher literal = Pattern.compile("\"([a-z_]+)\"").matcher(line);
          while (literal.find()) {
            names.add(literal.group(1));
          }
        }
        for (String name : names) {
          String owner = config.ownerOf.get(name);
          if (owner != null && !owner.equals(module)
              && !config.readers.getOrDefault(name, Set.of()).contains(module)) {
            out.add("COLLECTION_NAME " + module + " : " + rel + " names " + name + " owned by " + owner);
          }
        }
      }
    }
    return out;
  }

  // ---- helpers --------------------------------------------------------------------------------

  /** Module of a class: com.inventory.<module>.. or com.inventory.plugins.<vertical>.. */
  static String moduleOf(String className) {
    if (!className.startsWith(ROOT_PACKAGE)) {
      return null;
    }
    String[] p = className.split("\\.");
    if (p.length > 4 && p[2].equals("plugins")) {
      return "plugins/" + p[3];
    }
    return p.length > 3 ? p[2] : null;
  }

  private static String apiPackage(String module) {
    return ROOT_PACKAGE + module.replace('/', '.') + ".api";
  }

  private static boolean isApi(JavaClass c, String module) {
    String api = apiPackage(module);
    String pkg = c.getPackageName();
    return pkg.equals(api) || pkg.startsWith(api + ".");
  }

  private static boolean isCompositionRoot(JavaClass c) {
    return config.compositionRoot.stream().anyMatch(p -> c.getName().equals(p) || c.getName().startsWith(p + "."));
  }

  private static boolean isRecord(JavaClass c) {
    return c.getRawSuperclass().map(s -> s.getName().equals("java.lang.Record")).orElse(false);
  }

  private static String topLevel(String className) {
    int i = className.indexOf('$');
    return i < 0 ? className : className.substring(0, i);
  }

  /** Spring Data's rule: collection/value attribute, else the uncapitalised simple class name. */
  private static String collectionName(JavaClass c, JavaAnnotation<JavaClass> doc) {
    for (String attr : List.of("collection", "value")) {
      Object v = doc.get(attr).orElse("");
      if (v instanceof String s && !s.isBlank()) {
        return s;
      }
    }
    String simple = c.getSimpleName();
    return Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
  }

  private static String moduleOfSource(Path file) {
    Path rel = repoRoot.relativize(file);
    String first = rel.getName(0).toString();
    return switch (first) {
      case "core" -> rel.getName(1).toString();
      case "plugins" -> "plugins/" + rel.getName(1);
      case "app", "pluginengine" -> first;
      default -> null;
    };
  }

  private static List<Path> mainSourceFiles() throws IOException {
    try (Stream<Path> s = Files.walk(repoRoot)) {
      return s.filter(p -> p.toString().endsWith(".java"))
          .filter(p -> p.toString().contains("/src/main/java/"))
          .filter(p -> !p.toString().contains("/target/"))
          .sorted()
          .toList();
    }
  }

  private static Set<String> readBaseline(Path file) throws IOException {
    if (!Files.exists(file)) {
      return Set.of();
    }
    return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
        .map(String::strip)
        .filter(l -> !l.isEmpty() && !l.startsWith("#"))
        .collect(Collectors.toCollection(TreeSet::new));
  }

  private static Path findRepoRoot() {
    Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (p != null && !Files.exists(p.resolve("module-boundaries.yml"))) {
      p = p.getParent();
    }
    if (p == null) {
      throw new IllegalStateException("module-boundaries.yml not found above " + System.getProperty("user.dir"));
    }
    return p;
  }

  /** com.inventory dependencies declared in each module's pom.xml, keyed by module name. */
  private static Map<String, Set<String>> readPomEdges(Path root) throws IOException {
    Map<String, Path> poms = new TreeMap<>();
    try (Stream<Path> core = Files.list(root.resolve("core"));
        Stream<Path> plugins = Files.list(root.resolve("plugins"))) {
      core.filter(d -> Files.exists(d.resolve("pom.xml"))).forEach(d -> poms.put(d.getFileName().toString(), d));
      plugins.filter(d -> Files.exists(d.resolve("pom.xml")))
          .forEach(d -> poms.put("plugins/" + d.getFileName(), d));
    }
    poms.put("pluginengine", root.resolve("pluginengine"));
    poms.put("app", root.resolve("app"));
    // artifactId -> module name (plugins publish their vertical name as artifactId)
    Map<String, String> byArtifact = new HashMap<>();
    poms.keySet().forEach(m -> byArtifact.put(m.startsWith("plugins/") ? m.substring(8) : m, m));

    Pattern dep = Pattern.compile(
        "<dependency>\\s*<groupId>com\\.inventory</groupId>\\s*<artifactId>([^<]+)</artifactId>");
    Map<String, Set<String>> edges = new TreeMap<>();
    for (Map.Entry<String, Path> e : poms.entrySet()) {
      String xml = Files.readString(e.getValue().resolve("pom.xml"));
      xml = xml.replaceAll("(?s)<!--.*?-->", "");
      Set<String> deps = new TreeSet<>();
      Matcher m = dep.matcher(xml);
      while (m.find()) {
        String module = byArtifact.get(m.group(1));
        if (module != null) {
          deps.add(module);
        }
      }
      edges.put(e.getKey(), deps);
    }
    return edges;
  }

  /** Parsed module-boundaries.yml. */
  static final class Config {
    Set<String> libraries = new TreeSet<>();
    Map<String, Set<String>> dependsOn = new TreeMap<>();
    Map<String, String> ownerOf = new TreeMap<>();
    Map<String, Set<String>> readers = new TreeMap<>();
    Set<String> unmappedCollections = new TreeSet<>();
    List<String> compositionRoot = new ArrayList<>();

    @SuppressWarnings("unchecked")
    static Config read(Path file) throws IOException {
      Map<String, Object> y = new Yaml().load(Files.readString(file));
      Config c = new Config();
      c.libraries.addAll((Collection<String>) y.getOrDefault("libraries", List.of()));
      c.compositionRoot.addAll((Collection<String>) y.getOrDefault("compositionRoot", List.of()));
      c.unmappedCollections.addAll((Collection<String>) y.getOrDefault("unmappedCollections", List.of()));
      Map<String, Map<String, Object>> modules = (Map<String, Map<String, Object>>) y.get("modules");
      List<String> duplicates = new ArrayList<>();
      modules.forEach((name, body) -> {
        c.dependsOn.put(name, new TreeSet<>((Collection<String>) body.getOrDefault("dependsOn", List.of())));
        for (String coll : (Collection<String>) body.getOrDefault("collections", List.of())) {
          String prev = c.ownerOf.put(coll, name);
          if (prev != null) {
            duplicates.add(coll + " (" + prev + ", " + name + ")");
          }
        }
      });
      if (!duplicates.isEmpty()) {
        throw new IllegalStateException("collections with more than one owner: " + duplicates);
      }
      Map<String, Map<String, Object>> readers =
          (Map<String, Map<String, Object>>) y.getOrDefault("readers", new LinkedHashMap<>());
      readers.forEach((coll, body) -> {
        if (!c.ownerOf.containsKey(coll)) {
          throw new IllegalStateException("readers lists unknown collection " + coll);
        }
        Object reason = body.get("reason");
        if (!(reason instanceof String s) || s.isBlank()) {
          throw new IllegalStateException("readers entry for " + coll + " needs a reason");
        }
        c.readers.put(coll, new TreeSet<>((Collection<String>) body.get("modules")));
      });
      return c;
    }
  }
}
