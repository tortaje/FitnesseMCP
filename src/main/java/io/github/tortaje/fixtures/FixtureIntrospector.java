package io.github.tortaje.fixtures;

import io.github.tortaje.protocol.ToolException;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Reflects on classes of a page class path without initialising them: classes are loaded with
 * {@code initialize=false} through an isolated class loader.
 */
final class FixtureIntrospector implements AutoCloseable {
  private static final List<String> LIFECYCLE =
      List.of("execute", "reset", "table", "beginTable", "endTable");

  private final List<String> classpath;
  private final URLClassLoader loader;

  FixtureIntrospector(List<String> classpath) {
    this.classpath = classpath;
    List<URL> urls = new ArrayList<>();
    for (String entry : classpath) {
      try {
        urls.add(new File(entry).toURI().toURL());
      } catch (MalformedURLException e) {
        // an unusable entry cannot contribute classes; ignore it
      }
    }
    this.loader = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
  }

  /** Names of public, concrete, top-level classes directly in one of the packages, sorted. */
  List<String> listClasses(Set<String> packages, int max) {
    Set<String> candidates = new TreeSet<>();
    for (String entry : classpath) {
      File f = new File(entry);
      if (f.isDirectory()) {
        for (String pkg : packages) scanDirectory(f, pkg, candidates);
      } else if (f.isFile()) {
        scanJar(f, packages, candidates);
      }
    }
    List<String> result = new ArrayList<>();
    for (String name : candidates) {
      if (result.size() >= max) break;
      try {
        Class<?> c = Class.forName(name, false, loader);
        int m = c.getModifiers();
        if (Modifier.isPublic(m) && !Modifier.isAbstract(m) && !c.isInterface()) result.add(name);
      } catch (ClassNotFoundException | LinkageError e) {
        // class cannot be loaded with this class path: not usable as a fixture
      }
    }
    return result;
  }

  private static void scanDirectory(File root, String pkg, Set<String> out) {
    File[] files = new File(root, pkg.replace('.', '/')).listFiles();
    if (files == null) return;
    for (File f : files) addIfClass(f.getName(), pkg, out);
  }

  private static void scanJar(File file, Set<String> packages, Set<String> out) {
    try (JarFile jar = new JarFile(file)) {
      jar.stream().forEach(e -> {
        String n = e.getName();
        int slash = n.lastIndexOf('/');
        if (slash < 0 || e.isDirectory()) return;
        String pkg = n.substring(0, slash).replace('/', '.');
        if (packages.contains(pkg)) addIfClass(n.substring(slash + 1), pkg, out);
      });
    } catch (IOException e) {
      // unreadable archive: skip it
    }
  }

  private static void addIfClass(String fileName, String pkg, Set<String> out) {
    if (!fileName.endsWith(".class") || fileName.indexOf('$') >= 0) return;
    String simple = fileName.substring(0, fileName.length() - ".class".length());
    if (simple.equals("package-info") || simple.equals("module-info")) return;
    out.add(pkg + "." + simple);
  }

  JSONObject describe(String className) throws ToolException {
    Class<?> c;
    try {
      c = Class.forName(className, false, loader);
    } catch (ClassNotFoundException e) {
      throw new ToolException("Class '" + className + "' not found on the page class path: check "
          + "the name and the !path entries, or use list_fixtures to see available classes");
    } catch (LinkageError e) {
      throw missing(className, e);
    }
    JSONArray constructors = new JSONArray();
    JSONArray methods = new JSONArray();
    try {
      Constructor<?>[] ctors = c.getConstructors();
      Arrays.sort(ctors, Comparator.comparing(k -> typeNames(k.getParameterTypes()).toString()));
      for (Constructor<?> k : ctors) {
        constructors.put(new JSONObject().put("parameters", typeNames(k.getParameterTypes())));
      }
      List<Method> sorted = new ArrayList<>();
      for (Method m : c.getMethods()) {
        if (m.getDeclaringClass() != Object.class && !m.isBridge() && !m.isSynthetic()) sorted.add(m);
      }
      sorted.sort(Comparator.comparing(Method::getName)
          .thenComparing(m -> typeNames(m.getParameterTypes()).toString()));
      for (Method m : sorted) methods.put(describeMethod(m));
    } catch (LinkageError e) {
      throw missing(className, e);
    }
    return new JSONObject().put("className", className)
        .put("constructors", constructors).put("methods", methods);
  }

  private static JSONObject describeMethod(Method m) {
    Class<?>[] params = m.getParameterTypes();
    Class<?> ret = m.getReturnType();
    List<String> usage = new ArrayList<>();
    String name = m.getName();
    if (name.length() > 3 && name.startsWith("set") && params.length == 1) usage.add("decision-input");
    if (params.length == 0 && ret != void.class) usage.add("decision-output");
    if (ret == boolean.class || ret == Boolean.class) usage.add("script-check");
    if (LIFECYCLE.contains(name)) usage.add("lifecycle");
    return new JSONObject().put("name", name).put("parameters", typeNames(params))
        .put("returnType", ret.getTypeName()).put("slimUsage", new JSONArray(usage));
  }

  private static List<String> typeNames(Class<?>[] types) {
    List<String> names = new ArrayList<>();
    for (Class<?> t : types) names.add(t.getTypeName());
    return Collections.unmodifiableList(names);
  }

  private static ToolException missing(String className, LinkageError e) {
    String detail = e.getMessage() == null ? e.getClass().getSimpleName()
        : e.getMessage().replace('/', '.');
    return new ToolException("Class '" + className + "' cannot be inspected because a class it "
        + "depends on is missing from the page class path (" + detail
        + "): add the library with a !path entry");
  }

  @Override public void close() {
    try {
      loader.close();
    } catch (IOException e) {
      // nothing useful to do
    }
  }
}
