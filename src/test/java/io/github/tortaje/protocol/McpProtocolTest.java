package io.github.tortaje.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tortaje.testsupport.LogCapture;
import java.util.logging.Level;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class McpProtocolTest {
  static class EchoTool implements Tool {
    public String name() { return "echo"; }
    public String description() { return "Echoes the text."; }
    public JSONObject inputSchema() {
      return new JSONObject().put("type", "object")
          .put("properties", new JSONObject().put("text", new JSONObject().put("type", "string")))
          .put("required", new JSONArray().put("text"));
    }
    public ToolResult call(JSONObject args) throws ToolException {
      String text = args.getString("text");
      if (text.equals("boom")) throw new ToolException("boom!");
      if (text.equals("crash")) throw new RuntimeException("kaput");
      if (text.equals("nomessage")) throw new IllegalStateException();
      if (text.equals("overflow")) throw new StackOverflowError();
      if (text.equals("linkage")) throw new NoClassDefFoundError("demo/Missing");
      return ToolResult.json(new JSONObject().put("echo", text));
    }
  }

  ToolRegistry registry = new ToolRegistry();
  McpProtocol protocol;

  @BeforeEach void setUp() {
    registry.register(new EchoTool());
    protocol = new McpProtocol(registry, "1.2.3");
  }

  private JSONObject request(String method, Object params) {
    JSONObject req = new JSONObject().put("jsonrpc", "2.0").put("id", 7).put("method", method);
    if (params != null) req.put("params", params);
    return req;
  }

  private JSONObject call(String method, JSONObject params) {
    return new JSONObject(protocol.handle(request(method, params).toString()).orElseThrow());
  }

  private JSONObject callTool(String name, JSONObject args) {
    return call("tools/call", new JSONObject().put("name", name).put("arguments", args));
  }

  @Test void initializeEchoesSupportedVersion() {
    JSONObject r = call("initialize", new JSONObject().put("protocolVersion", "2025-06-18")
        .put("capabilities", new JSONObject())
        .put("clientInfo", new JSONObject().put("name", "t").put("version", "1")));
    assertThat(r.getJSONObject("result").getString("protocolVersion")).isEqualTo("2025-06-18");
    assertThat(r.getJSONObject("result").getJSONObject("capabilities").has("tools")).isTrue();
    assertThat(r.getJSONObject("result").getJSONObject("serverInfo").getString("name"))
        .isEqualTo("fitnesse-mcp-plugin");
    assertThat(r.getJSONObject("result").getJSONObject("serverInfo").getString("version"))
        .isEqualTo("1.2.3");
  }

  @Test void initializeFallsBackToNewestVersion() {
    JSONObject r = call("initialize", new JSONObject().put("protocolVersion", "1999-01-01"));
    assertThat(r.getJSONObject("result").getString("protocolVersion")).isEqualTo("2025-11-25");
  }

  @Test void notificationReturnsEmpty() {
    assertThat(protocol.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
        .isEmpty();
  }

  @Test void pingReturnsEmptyResult() {
    JSONObject r = call("ping", null);
    assertThat(r.getJSONObject("result").length()).isZero();
    assertThat(r.getInt("id")).isEqualTo(7);
    assertThat(r.getString("jsonrpc")).isEqualTo("2.0");
  }

  @Test void stringIdIsEchoedUnchanged() {
    String raw = "{\"jsonrpc\":\"2.0\",\"id\":\"abc\",\"method\":\"ping\"}";
    JSONObject r = new JSONObject(protocol.handle(raw).orElseThrow());
    assertThat(r.getString("id")).isEqualTo("abc");
  }

  @Test void toolsListExposesSchema() {
    JSONObject tool = call("tools/list", null).getJSONObject("result")
        .getJSONArray("tools").getJSONObject(0);
    assertThat(tool.getString("name")).isEqualTo("echo");
    assertThat(tool.getString("description")).isNotEmpty();
    assertThat(tool.getJSONObject("inputSchema").getString("type")).isEqualTo("object");
  }

  @Test void toolsCallReturnsContent() {
    JSONObject result = callTool("echo", new JSONObject().put("text", "hi")).getJSONObject("result");
    JSONObject content = result.getJSONArray("content").getJSONObject(0);
    assertThat(content.getString("type")).isEqualTo("text");
    assertThat(new JSONObject(content.getString("text")).getString("echo")).isEqualTo("hi");
    assertThat(result.getBoolean("isError")).isFalse();
  }

  @Test void toolExceptionBecomesIsError() {
    JSONObject result = callTool("echo", new JSONObject().put("text", "boom")).getJSONObject("result");
    assertThat(result.getBoolean("isError")).isTrue();
    assertThat(result.getJSONArray("content").getJSONObject(0).getString("text")).isEqualTo("boom!");
  }

  @Test void unexpectedExceptionBecomesIsErrorAndIsLogged() {
    try (LogCapture log = LogCapture.of(McpProtocol.class)) {
      JSONObject result = callTool("echo", new JSONObject().put("text", "crash")).getJSONObject("result");
      assertThat(result.getBoolean("isError")).isTrue();
      assertThat(result.getJSONArray("content").getJSONObject(0).getString("text"))
          .isEqualTo("RuntimeException: kaput");
      assertThat(log.records()).hasSize(1);
      assertThat(log.records().get(0).getLevel()).isEqualTo(Level.WARNING);
      assertThat(log.records().get(0).getMessage()).contains("echo");
      assertThat(log.records().get(0).getThrown()).hasMessage("kaput");
    }
  }

  @Test void exceptionWithoutMessageHasNoNullSuffix() {
    try (LogCapture log = LogCapture.of(McpProtocol.class)) {
      JSONObject result = callTool("echo", new JSONObject().put("text", "nomessage")).getJSONObject("result");
      assertThat(result.getJSONArray("content").getJSONObject(0).getString("text"))
          .isEqualTo("IllegalStateException");
    }
  }

  @Test void stackOverflowAndLinkageErrorsBecomeIsErrorAndAreLogged() {
    try (LogCapture log = LogCapture.of(McpProtocol.class)) {
      JSONObject overflow = callTool("echo", new JSONObject().put("text", "overflow")).getJSONObject("result");
      assertThat(overflow.getBoolean("isError")).isTrue();
      assertThat(overflow.getJSONArray("content").getJSONObject(0).getString("text"))
          .isEqualTo("StackOverflowError");
      JSONObject linkage = callTool("echo", new JSONObject().put("text", "linkage")).getJSONObject("result");
      assertThat(linkage.getJSONArray("content").getJSONObject(0).getString("text"))
          .isEqualTo("NoClassDefFoundError: demo/Missing");
      assertThat(log.records()).hasSize(2).allSatisfy(r -> {
        assertThat(r.getLevel()).isEqualTo(Level.WARNING);
        assertThat(r.getMessage()).contains("echo");
      });
      assertThat(log.records().get(0).getThrown()).isInstanceOf(StackOverflowError.class);
    }
  }

  @Test void failureOutsideToolCallBecomesInternalErrorResponse() {
    registry.register(new EchoTool() {
      @Override public String name() { return "broken"; }
      @Override public String description() { throw new IllegalStateException("no description"); }
    });
    try (LogCapture log = LogCapture.of(McpProtocol.class)) {
      JSONObject r = call("tools/list", null);
      assertThat(r.get("id")).isEqualTo(7);
      assertThat(r.getJSONObject("error").getInt("code")).isEqualTo(-32603);
      assertThat(r.getJSONObject("error").getString("message"))
          .contains("tools/list").contains("IllegalStateException: no description");
      assertThat(log.records()).singleElement().satisfies(rec -> assertThat(rec.getLevel()).isEqualTo(Level.WARNING));
    }
  }

  @Test void parseErrorHasNullId() {
    JSONObject r = new JSONObject(protocol.handle("{oops").orElseThrow());
    assertThat(r.getJSONObject("error").getInt("code")).isEqualTo(-32700);
    assertThat(r.isNull("id")).isTrue();
    assertThat(r.has("id")).isTrue();
  }

  @Test void batchIsInvalidRequest() {
    JSONObject r = new JSONObject(protocol.handle("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]").orElseThrow());
    assertThat(r.getJSONObject("error").getInt("code")).isEqualTo(-32600);
  }

  @Test void missingJsonrpcIsInvalidRequest() {
    JSONObject r = new JSONObject(protocol.handle("{\"id\":1,\"method\":\"ping\"}").orElseThrow());
    assertThat(r.getJSONObject("error").getInt("code")).isEqualTo(-32600);
  }

  @Test void unknownMethod() {
    assertThat(call("nope/nothing", null).getJSONObject("error").getInt("code")).isEqualTo(-32601);
  }

  @Test void unknownTool() {
    JSONObject r = callTool("nope", new JSONObject());
    assertThat(r.getJSONObject("error").getInt("code")).isEqualTo(-32602);
    assertThat(r.getJSONObject("error").getString("message")).contains("nope");
  }

  @Test void argumentsViolatingSchema() {
    JSONObject r = callTool("echo", new JSONObject());
    assertThat(r.getJSONObject("error").getInt("code")).isEqualTo(-32602);
    assertThat(r.getJSONObject("error").getString("message")).contains("text");
  }

  @Test void duplicateToolNameIsRejected() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> registry.register(new EchoTool()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
