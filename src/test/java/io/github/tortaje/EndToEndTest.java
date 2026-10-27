package io.github.tortaje;

import static org.assertj.core.api.Assertions.assertThat;

import fitnesse.authentication.OneUserAuthenticator;
import io.github.tortaje.testsupport.TestWiki;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Set;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Drives the plugin over HTTP through the MCP listener that the plugin starts for its context. */
class EndToEndTest {
  private static final Set<String> TOOLS = Set.of(
      "get_page", "list_pages", "search_pages", "save_page", "move_page", "delete_page",
      "list_files", "get_file", "upload_file", "delete_file",
      "run_tests", "get_run_status", "get_run_results", "cancel_run",
      "list_fixtures", "describe_fixture");

  @TempDir Path tmp;
  TestWiki wiki;
  int mcpPort;
  final HttpClient http = HttpClient.newHttpClient();
  int nextId = 1;

  private void start(TestWiki base) throws Exception {
    mcpPort = TestWiki.freePort();
    wiki = base.withMcpPort(mcpPort);
  }

  private URI endpoint() {
    return URI.create("http://127.0.0.1:" + mcpPort + "/mcp");
  }

  @AfterEach void tearDown() {
    if (wiki != null) wiki.close();
  }

  private HttpResponse<String> post(String body, String... headers) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(endpoint())
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private JSONObject rpc(String method, JSONObject params) throws Exception {
    JSONObject req = new JSONObject().put("jsonrpc", "2.0").put("id", nextId++).put("method", method);
    if (params != null) req.put("params", params);
    HttpResponse<String> r = post(req.toString());
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
    assertThat(r.headers().firstValue("Mcp-Session-Id")).isEmpty();
    return new JSONObject(r.body());
  }

  private JSONObject tool(String name, JSONObject args) throws Exception {
    JSONObject result = rpc("tools/call", new JSONObject().put("name", name).put("arguments", args))
        .getJSONObject("result");
    assertThat(result.getBoolean("isError")).as("tool %s: %s", name, result).isFalse();
    return new JSONObject(result.getJSONArray("content").getJSONObject(0).getString("text"));
  }

  private JSONObject runToEnd(String path) throws Exception {
    String runId = tool("run_tests", new JSONObject().put("path", path)).getString("runId");
    long deadline = System.nanoTime() + 60_000_000_000L;
    while (System.nanoTime() < deadline) {
      String state = tool("get_run_status", new JSONObject().put("runId", runId)).getString("state");
      if (state.equals("completed")) {
        return tool("get_run_results", new JSONObject().put("runId", runId).put("onlyFailures", false));
      }
      assertThat(state).isIn("queued", "running");
      Thread.sleep(100);
    }
    throw new AssertionError("run did not finish");
  }

  private static String divisionPage(String expected) {
    return TestWiki.slimHeader() + "|Division|\n|numerator|denominator|quotient?|\n|10|2|" + expected + "|\n";
  }

  @Test void fullCycleOverHttp() throws Exception {
    start(TestWiki.create(tmp));

    JSONObject init = rpc("initialize", new JSONObject()
        .put("protocolVersion", "2025-06-18").put("capabilities", new JSONObject())
        .put("clientInfo", new JSONObject().put("name", "e2e").put("version", "1")));
    assertThat(init.getJSONObject("result").getJSONObject("serverInfo").getString("name"))
        .isEqualTo("fitnesse-mcp-plugin");
    HttpResponse<String> initialized =
        post(new JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized").toString());
    assertThat(initialized.statusCode()).isEqualTo(202);

    JSONArray tools = rpc("tools/list", null).getJSONObject("result").getJSONArray("tools");
    Set<String> names = new TreeSet<>();
    for (int i = 0; i < tools.length(); i++) names.add(tools.getJSONObject(i).getString("name"));
    assertThat(names).isEqualTo(new TreeSet<>(TOOLS));

    tool("save_page", new JSONObject().put("path", "DivisionTest").put("type", "Test")
        .put("content", divisionPage("4.0")));
    JSONObject fixtures = tool("list_fixtures", new JSONObject().put("path", "DivisionTest"));
    assertThat(fixtures.getJSONArray("classes").toString()).contains("demo.Division");
    JSONObject red = runToEnd("DivisionTest");
    JSONObject page = red.getJSONArray("pages").getJSONObject(0);
    assertThat(page.getJSONObject("counts").getInt("wrong")).isEqualTo(1);
    assertThat(page.getJSONArray("failures").length()).isEqualTo(1);

    tool("save_page", new JSONObject().put("path", "DivisionTest").put("content", divisionPage("5.0")));
    JSONObject green = runToEnd("DivisionTest");
    assertThat(green.getJSONArray("pages").getJSONObject(0).getJSONObject("counts").getInt("right"))
        .isEqualTo(1);
  }

  @Test void foreignOriginGets403OverHttp() throws Exception {
    start(TestWiki.create(tmp));
    String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
    assertThat(post(body, "Origin", "http://evil.example").statusCode()).isEqualTo(403);
    assertThat(post(body, "Origin", "http://localhost:3000").statusCode()).isEqualTo(200);
  }

  @Test void basicAuthRequiredWhenAuthenticatorSet() throws Exception {
    start(TestWiki.create(tmp).withAuthenticator(new OneUserAuthenticator("u", "p")));
    String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
    assertThat(post(body).statusCode()).isEqualTo(401);
    String basic = "Basic " + Base64.getEncoder().encodeToString("u:p".getBytes(StandardCharsets.UTF_8));
    assertThat(post(body, "Authorization", basic).statusCode()).isEqualTo(200);
    String bad = "Basic " + Base64.getEncoder().encodeToString("u:x".getBytes(StandardCharsets.UTF_8));
    assertThat(post(body, "Authorization", bad).statusCode()).isEqualTo(401);
  }

  @Test void getIsRejectedWith405() throws Exception {
    start(TestWiki.create(tmp));
    HttpResponse<String> r = http.send(
        HttpRequest.newBuilder(endpoint()).GET().build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).isEqualTo(405);
  }

  /** FitNesse's own HTTP server URL-decoded POST bodies, so a lone '%' gave HTTP 400 there. */
  @Test void percentSignsSurviveTheRoundTrip() throws Exception {
    start(TestWiki.create(tmp));
    String content = "!define COMMAND_PATTERN {java -cp %p %m}\n\n|discount|10%|\n|100%|%zz|%|\n";
    tool("save_page", new JSONObject().put("path", "PercentPage").put("content", content));

    assertThat(tool("get_page", new JSONObject().put("path", "PercentPage")).getString("content"))
        .isEqualTo(content);
    JSONObject found = tool("search_pages", new JSONObject().put("query", "100%"));
    assertThat(found.getJSONArray("results").getJSONObject(0).getString("path")).isEqualTo("PercentPage");
  }
}
