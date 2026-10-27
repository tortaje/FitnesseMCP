package io.github.tortaje;

import static org.assertj.core.api.Assertions.assertThat;

import fitnesse.authentication.OneUserAuthenticator;
import io.github.tortaje.testsupport.LogCapture;
import io.github.tortaje.testsupport.TestWiki;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.OptionalInt;
import java.util.logging.Level;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpHttpServerTest {
  static final String INITIALIZE =
      "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}";
  static final String TOOLS_LIST = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";

  @TempDir Path tmp;
  final HttpClient http = HttpClient.newHttpClient();
  TestWiki wiki;
  int port;

  @AfterEach void tearDown() {
    if (wiki != null) wiki.close();
  }

  private void start() throws Exception {
    start(TestWiki.create(tmp));
  }

  private void start(TestWiki base) throws Exception {
    port = TestWiki.freePort();
    wiki = base.withMcpPort(port);
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
  }

  private HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private HttpResponse<String> post(String body, String... headers) throws Exception {
    HttpRequest.Builder b = request("/mcp").header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
    return send(b);
  }

  @Test void pluginStartsListenerForContextBuiltWithMcpPort() throws Exception {
    start();
    assertThat(McpHttpServer.running(wiki.context())).isPresent();
    assertThat(McpHttpServer.running(wiki.context()).get().port()).isEqualTo(port);
  }

  @Test void postInitializeReturnsJson() throws Exception {
    start();
    HttpResponse<String> r = post(INITIALIZE);
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.headers().firstValue("Content-Type").orElse("")).isEqualTo("application/json; charset=utf-8");
    assertThat(r.headers().firstValue("Mcp-Session-Id")).isEmpty();
    assertThat(new JSONObject(r.body()).getJSONObject("result").getString("protocolVersion"))
        .isEqualTo("2025-06-18");
  }

  @Test void bodyIsReadAsUtf8() throws Exception {
    start();
    HttpResponse<String> r = post("{\"jsonrpc\":\"2.0\",\"id\":\"é%zz\",\"method\":\"ping\"}");
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(new JSONObject(r.body()).getString("id")).isEqualTo("é%zz");
  }

  @Test void notificationReturns202() throws Exception {
    start();
    HttpResponse<String> r = post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
    assertThat(r.statusCode()).isEqualTo(202);
    assertThat(r.body()).isEmpty();
  }

  @Test void getAndDeleteReturn405WithAllow() throws Exception {
    start();
    for (HttpRequest.Builder b : List.of(request("/mcp").GET(), request("/mcp").DELETE())) {
      HttpResponse<String> r = send(b);
      assertThat(r.statusCode()).isEqualTo(405);
      assertThat(r.headers().firstValue("Allow")).hasValue("POST");
    }
  }

  @Test void otherPathsReturn404() throws Exception {
    start();
    for (String path : List.of("/", "/mcpx", "/mcp/x", "/FrontPage")) {
      HttpResponse<String> r = send(request(path)
          .POST(HttpRequest.BodyPublishers.ofString(TOOLS_LIST, StandardCharsets.UTF_8)));
      assertThat(r.statusCode()).as(path).isEqualTo(404);
    }
  }

  @Test void foreignOrMalformedOriginRejected() throws Exception {
    start();
    assertThat(post(INITIALIZE, "Origin", "http://evil.example").statusCode()).isEqualTo(403);
    assertThat(post(INITIALIZE, "Origin", "http://localhost.evil.example").statusCode()).isEqualTo(403);
    assertThat(post(INITIALIZE, "Origin", "not a uri").statusCode()).isEqualTo(403);
  }

  @Test void localhostOriginsAccepted() throws Exception {
    start();
    for (String origin : List.of("http://localhost:3000", "http://127.0.0.1", "https://[::1]:8080")) {
      assertThat(post(INITIALIZE, "Origin", origin).statusCode()).as(origin).isEqualTo(200);
    }
  }

  @Test void basicAuthCheckedWithFitNesseAuthenticator() throws Exception {
    start(TestWiki.create(tmp).withAuthenticator(new OneUserAuthenticator("u", "p")));
    HttpResponse<String> anonymous = post(TOOLS_LIST);
    assertThat(anonymous.statusCode()).isEqualTo(401);
    assertThat(anonymous.headers().firstValue("WWW-Authenticate")).hasValue("Basic realm=\"FitNesse MCP\"");
    String good = "Basic " + Base64.getEncoder().encodeToString("u:p".getBytes(StandardCharsets.UTF_8));
    assertThat(post(TOOLS_LIST, "Authorization", good).statusCode()).isEqualTo(200);
    String bad = "Basic " + Base64.getEncoder().encodeToString("u:x".getBytes(StandardCharsets.UTF_8));
    assertThat(post(TOOLS_LIST, "Authorization", bad).statusCode()).isEqualTo(401);
    assertThat(post(TOOLS_LIST, "Authorization", "Basic !!!").statusCode()).isEqualTo(401);
  }

  @Test void unexpectedFailureReturns500WithJsonRpcError() throws Exception {
    start(TestWiki.create(tmp).withAuthenticator(new fitnesse.authentication.Authenticator() {
      @Override public boolean isAuthenticated(String user, String password) {
        throw new IllegalStateException("authenticator down");
      }
    }));
    try (LogCapture log = LogCapture.of(McpHttpServer.class)) {
      HttpResponse<String> r = post(TOOLS_LIST);
      assertThat(r.statusCode()).isEqualTo(500);
      assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
      assertThat(new JSONObject(r.body()).getJSONObject("error").getInt("code")).isEqualTo(-32603);
      assertThat(log.records()).singleElement()
          .satisfies(rec -> assertThat(rec.getThrown()).hasMessage("authenticator down"));
    }
  }

  @Test void bodyOverLimitReturns413() throws Exception {
    start();
    String pad = "x".repeat(McpHttpServer.MAX_BODY_BYTES);
    String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{\"p\":\"" + pad + "\"}}";
    assertThat(post(body).statusCode()).isEqualTo(413);
    // chunked (no Content-Length) is capped as well
    HttpResponse<String> chunked = send(request("/mcp").POST(HttpRequest.BodyPublishers.ofInputStream(
        () -> new java.io.ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))));
    assertThat(chunked.statusCode()).isEqualTo(413);
  }

  @Test void chunkedBodyWithinLimitIsAccepted() throws Exception {
    start();
    HttpResponse<String> r = send(request("/mcp").POST(HttpRequest.BodyPublishers.ofInputStream(
        () -> new java.io.ByteArrayInputStream(TOOLS_LIST.getBytes(StandardCharsets.UTF_8)))));
    assertThat(r.statusCode()).isEqualTo(200);
  }

  @Test void mcpPortOffStartsNothing() {
    wiki = TestWiki.create(tmp);
    assertThat(McpHttpServer.running(wiki.context())).isEmpty();
  }

  @Test void commandModeStartsNothing() throws Exception {
    wiki = TestWiki.create(tmp).withParameter("MCP_PORT", Integer.toString(TestWiki.freePort()))
        .withParameter("Command", "FrontPage?test");
    assertThat(McpHttpServer.running(wiki.context())).isEmpty();
  }

  @Test void bindFailureLogsWarningAndDoesNotThrow() throws Exception {
    try (ServerSocket occupied = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
         LogCapture log = LogCapture.of(McpHttpServer.class)) {
      wiki = TestWiki.create(tmp).withMcpPort(occupied.getLocalPort());
      assertThat(McpHttpServer.running(wiki.context())).isEmpty();
      assertThat(log.records()).anySatisfy(r -> {
        assertThat(r.getLevel()).isEqualTo(Level.WARNING);
        assertThat(r.getMessage()).contains(Integer.toString(occupied.getLocalPort())).contains("MCP_PORT");
      });
    }
  }

  @Test void portDefaultsToFitNessePortPlusOne() {
    assertThat(McpHttpServer.resolvePort(null, 8080)).isEqualTo(OptionalInt.of(8081));
    assertThat(McpHttpServer.resolvePort(" 9000 ", 8080)).isEqualTo(OptionalInt.of(9000));
    assertThat(McpHttpServer.resolvePort("off", 8080)).isEmpty();
    assertThat(McpHttpServer.resolvePort("OFF", 8080)).isEmpty();
  }

  @Test void invalidPortIsRejectedWithClearMessage() {
    for (String bad : List.of("abc", "-1", "70000")) {
      try {
        McpHttpServer.resolvePort(bad, 8080);
        throw new AssertionError("no exception for " + bad);
      } catch (IllegalArgumentException e) {
        assertThat(e.getMessage()).contains("MCP_PORT").contains(bad);
      }
    }
  }

  @Test void runtimeIsSharedPerContext(@TempDir Path other) {
    wiki = TestWiki.create(tmp);
    McpRuntime a = McpRuntime.forContext(wiki.context());
    assertThat(McpRuntime.forContext(wiki.context())).isSameAs(a);
    assertThat(McpRuntime.forContext(TestWiki.create(other).context())).isNotSameAs(a);
  }
}
