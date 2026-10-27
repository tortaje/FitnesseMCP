package io.github.tortaje.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tortaje.protocol.McpProtocol;
import io.github.tortaje.protocol.ToolRegistry;
import io.github.tortaje.testsupport.McpClient;
import io.github.tortaje.testsupport.TestWiki;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FixtureToolsTest {
  @TempDir Path tmp;
  TestWiki wiki;
  McpClient client;

  @BeforeEach void setUp() {
    wiki = TestWiki.create(tmp);
    ToolRegistry registry = new ToolRegistry();
    FixtureTools.registerAll(registry, wiki.context());
    client = new McpClient(new McpProtocol(registry, "test"));
    wiki.page("Suite", TestWiki.classpathHeader());
    wiki.page("Suite.SetUp", "|import|\n|demo|\n");
    wiki.testPage("Suite.Test", "content");
    wiki.testPage("Plain", TestWiki.classpathHeader());
  }

  @AfterEach void tearDown() { wiki.close(); }

  private static JSONObject args(String... kv) {
    JSONObject o = new JSONObject();
    for (int i = 0; i < kv.length; i += 2) o.put(kv[i], kv[i + 1]);
    return o;
  }

  private static List<String> strings(JSONArray a) {
    List<String> l = new ArrayList<>();
    for (int i = 0; i < a.length(); i++) l.add(a.getString(i));
    return l;
  }

  @Test void listsFixturesFromImports() {
    JSONObject out = client.json(client.callTool("list_fixtures", args("path", "Suite.Test")));
    assertThat(strings(out.getJSONArray("classes")))
        .contains("demo.Division", "demo.Boom", "demo.StaticTrap").doesNotContain("demo.Hidden");
    assertThat(strings(out.getJSONArray("packages"))).containsExactly("demo");
    assertThat(out.getBoolean("truncated")).isFalse();
  }

  @Test void explicitPackageOverridesImports() {
    JSONObject out = client.json(client.callTool("list_fixtures", args("path", "Plain", "package", "demo")));
    assertThat(strings(out.getJSONArray("classes"))).contains("demo.Division").doesNotContain("demo.Hidden");
  }

  @Test void listsFixturesFromJarOnClasspath() throws Exception {
    Path jar = tmp.resolve("fixtures.jar");
    try (var out = new java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(jar));
         var in = demo.Division.class.getResourceAsStream("Division.class")) {
      out.putNextEntry(new java.util.jar.JarEntry("demo/Division.class"));
      in.transferTo(out);
      out.closeEntry();
    }
    wiki.testPage("Jarred", "!path " + jar + "\n");
    JSONObject out = client.json(client.callTool("list_fixtures", args("path", "Jarred", "package", "demo")));
    assertThat(strings(out.getJSONArray("classes"))).containsExactly("demo.Division");
  }

  @Test void noPackageAndNoImportsIsError() {
    String msg = client.errorText(client.callTool("list_fixtures", args("path", "Plain")));
    assertThat(msg).contains("package");
  }

  @Test void describeGivesSlimHints() {
    JSONObject d = client.json(client.callTool("describe_fixture",
        args("path", "Suite.Test", "className", "demo.Division")));
    assertThat(d.getString("className")).isEqualTo("demo.Division");
    assertThat(d.getJSONArray("constructors")).hasSize(1);
    assertThat(d.getJSONArray("constructors").getJSONObject(0).getJSONArray("parameters").length()).isZero();
    JSONObject setter = method(d, "setNumerator");
    assertThat(strings(setter.getJSONArray("slimUsage"))).contains("decision-input");
    assertThat(strings(setter.getJSONArray("parameters"))).containsExactly("double");
    JSONObject quotient = method(d, "quotient");
    assertThat(strings(quotient.getJSONArray("slimUsage"))).contains("decision-output");
    assertThat(quotient.getString("returnType")).isEqualTo("double");
  }

  private static JSONObject method(JSONObject d, String name) {
    JSONArray ms = d.getJSONArray("methods");
    for (int i = 0; i < ms.length(); i++) {
      if (ms.getJSONObject(i).getString("name").equals(name)) return ms.getJSONObject(i);
    }
    throw new AssertionError("no method " + name + " in " + d);
  }

  @Test void staticInitializerNotRun() {
    client.json(client.callTool("list_fixtures", args("path", "Suite.Test")));
    client.json(client.callTool("describe_fixture",
        args("path", "Suite.Test", "className", "demo.StaticTrap")));
    assertThat(System.getProperty("tortaje.trap")).isNull();
  }

  @Test void unknownClassIsError() {
    String msg = client.errorText(client.callTool("describe_fixture",
        args("path", "Suite.Test", "className", "demo.Nope")));
    assertThat(msg).contains("demo.Nope");
  }
}
