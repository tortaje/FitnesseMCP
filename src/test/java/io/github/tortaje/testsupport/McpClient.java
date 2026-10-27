package io.github.tortaje.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tortaje.protocol.McpProtocol;
import org.json.JSONObject;

/** Calls an in-process {@link McpProtocol} and unwraps tool results. */
public final class McpClient {
  private final McpProtocol protocol;
  private int nextId = 1;

  public McpClient(McpProtocol protocol) { this.protocol = protocol; }

  /** Performs tools/call and returns the JSON-RPC {@code result} object. */
  public JSONObject callTool(String name, JSONObject args) {
    JSONObject req = new JSONObject()
        .put("jsonrpc", "2.0").put("id", nextId++).put("method", "tools/call")
        .put("params", new JSONObject().put("name", name).put("arguments", args));
    String raw = protocol.handle(req.toString()).orElseThrow();
    JSONObject response = new JSONObject(raw);
    assertThat(response.has("error")).as("JSON-RPC error: %s", raw).isFalse();
    return response.getJSONObject("result");
  }

  /** Parses the JSON text of the first content item of a successful result. */
  public JSONObject json(JSONObject result) {
    assertThat(result.getBoolean("isError")).as("tool result: %s", result).isFalse();
    return new JSONObject(text(result));
  }

  /** Asserts the result is an error and returns its message. */
  public String errorText(JSONObject result) {
    assertThat(result.getBoolean("isError")).as("tool result: %s", result).isTrue();
    return text(result);
  }

  private static String text(JSONObject result) {
    return result.getJSONArray("content").getJSONObject(0).getString("text");
  }
}
