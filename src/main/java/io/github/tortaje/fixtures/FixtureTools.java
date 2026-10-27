package io.github.tortaje.fixtures;

import fitnesse.FitNesseContext;
import fitnesse.testrunner.ClassPathBuilder;
import fitnesse.wiki.WikiPage;
import io.github.tortaje.protocol.Tool;
import io.github.tortaje.protocol.ToolException;
import io.github.tortaje.protocol.ToolRegistry;
import io.github.tortaje.protocol.ToolResult;
import io.github.tortaje.wiki.PagePaths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Fixture discovery tools: list_fixtures and describe_fixture. */
public final class FixtureTools {
  static final int MAX_CLASSES = 500;

  private static final String PATH_HELP = "Test page whose !path entries and import tables "
      + "define the class path, as dot-separated WikiWords, e.g. FrontPage.MySuite.MyTest.";

  private FixtureTools() {}

  public static void registerAll(ToolRegistry r, FitNesseContext ctx) {
    r.register(new ListFixtures(ctx));
    r.register(new DescribeFixture(ctx));
  }

  private static JSONObject str(String description) {
    return new JSONObject().put("type", "string").put("description", description);
  }

  private static final class ListFixtures implements Tool {
    private final FitNesseContext ctx;

    ListFixtures(FitNesseContext ctx) { this.ctx = ctx; }

    public String name() { return "list_fixtures"; }

    public String description() {
      return "Lists the public, concrete Slim fixture classes available to a test page, as "
          + "{packages,classes,truncated} with at most " + MAX_CLASSES + " classes. Packages come "
          + "from the page's import tables (including ancestors and SetUp/SuiteSetUp) unless "
          + "'package' is given. Classes are never initialised. " + PATH_HELP;
    }

    public JSONObject inputSchema() {
      return new JSONObject().put("type", "object").put("properties", new JSONObject()
          .put("path", str(PATH_HELP))
          .put("package", str("Java package to list instead of the imported ones, e.g. com.acme.fixtures.")))
          .put("required", new JSONArray().put("path"));
    }

    public ToolResult call(JSONObject args) throws ToolException {
      WikiPage page = PagePaths.resolve(ctx.getRootPage(), args.getString("path"));
      Set<String> packages = new LinkedHashSet<>();
      String explicit = args.optString("package", "").trim();
      if (!explicit.isEmpty()) {
        packages.add(explicit);
      } else {
        packages.addAll(ImportScanner.packagesFor(page));
      }
      if (packages.isEmpty()) {
        throw new ToolException("No Slim import table found for this page: pass the Java "
            + "'package' argument (e.g. 'com.acme.fixtures') or add an |import| table to the page or its SetUp");
      }
      List<String> classes;
      try (FixtureIntrospector in = new FixtureIntrospector(new ClassPathBuilder().getClassPath(page))) {
        classes = in.listClasses(packages, MAX_CLASSES + 1);
      }
      boolean truncated = classes.size() > MAX_CLASSES;
      if (truncated) classes = new ArrayList<>(classes.subList(0, MAX_CLASSES));
      return ToolResult.json(new JSONObject()
          .put("packages", new JSONArray(new ArrayList<>(packages)))
          .put("classes", new JSONArray(classes))
          .put("truncated", truncated));
    }
  }

  private static final class DescribeFixture implements Tool {
    private final FitNesseContext ctx;

    DescribeFixture(FitNesseContext ctx) { this.ctx = ctx; }

    public String name() { return "describe_fixture"; }

    public String description() {
      return "Describes a fixture class without initialising it: public constructors and "
          + "methods (declared or inherited, except Object's) with parameter and return types and "
          + "slimUsage hints (decision-input, decision-output, script-check, lifecycle). "
          + PATH_HELP;
    }

    public JSONObject inputSchema() {
      return new JSONObject().put("type", "object").put("properties", new JSONObject()
          .put("path", str(PATH_HELP))
          .put("className", str("Fully qualified class name, e.g. com.acme.fixtures.Division.")))
          .put("required", new JSONArray().put("path").put("className"));
    }

    public ToolResult call(JSONObject args) throws ToolException {
      WikiPage page = PagePaths.resolve(ctx.getRootPage(), args.getString("path"));
      String className = args.getString("className").trim();
      try (FixtureIntrospector in = new FixtureIntrospector(new ClassPathBuilder().getClassPath(page))) {
        return ToolResult.json(in.describe(className));
      }
    }
  }
}
