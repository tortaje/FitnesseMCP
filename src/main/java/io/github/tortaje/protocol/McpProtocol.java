package io.github.tortaje.protocol;

import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** FitNesse-agnostic MCP JSON-RPC 2.0 dispatcher. Stateless: no session is ever created. */
public class McpProtocol {
  private static final Logger LOG = Logger.getLogger(McpProtocol.class.getName());
  static final List<String> SUPPORTED_VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26");
  static final String SERVER_NAME = "fitnesse-mcp-plugin";

  private static final int PARSE_ERROR = -32700;
  private static final int INVALID_REQUEST = -32600;
  private static final int METHOD_NOT_FOUND = -32601;
  private static final int INVALID_PARAMS = -32602;
  private static final int INTERNAL_ERROR = -32603;

  private final ToolRegistry registry;
  private final String serverVersion;

  public McpProtocol(ToolRegistry registry, String serverVersion) {
    this.registry = registry;
    this.serverVersion = serverVersion;
  }

  /** Handles one raw JSON-RPC message; empty for notifications, which get no response. */
  public Optional<String> handle(String rawMessage) {
    Object parsed;
    try {
      parsed = new org.json.JSONTokener(rawMessage).nextValue();
      if (!(parsed instanceof JSONObject) && !(parsed instanceof JSONArray)) {
        throw new JSONException("Not a JSON object");
      }
    } catch (JSONException | NullPointerException e) {
      return Optional.of(error(JSONObject.NULL, PARSE_ERROR,
          "Parse error: the request body is not valid JSON.").toString());
    }
    if (!(parsed instanceof JSONObject)) {
      return Optional.of(error(JSONObject.NULL, INVALID_REQUEST,
          "Invalid request: JSON-RPC batches are not supported; send one message per request.")
          .toString());
    }
    JSONObject msg = (JSONObject) parsed;
    boolean hasId = msg.has("id");
    Object id = hasId ? msg.get("id") : JSONObject.NULL;

    if (!"2.0".equals(msg.opt("jsonrpc")) || !(msg.opt("method") instanceof String)) {
      return Optional.of(error(id, INVALID_REQUEST,
          "Invalid request: expected {\"jsonrpc\":\"2.0\",\"method\":<string>,...}.").toString());
    }
    if (!hasId) {
      return Optional.empty();
    }
    JSONObject params = msg.optJSONObject("params");
    if (params == null) params = new JSONObject();
    String method = msg.getString("method");
    try {
      return Optional.of(dispatch(id, method, params).toString());
    } catch (Exception | LinkageError | StackOverflowError e) {
      // Tool calls are guarded in toolsCall; this keeps every other failure a JSON-RPC reply.
      LOG.log(Level.WARNING, "MCP method '" + method + "' failed", e);
      return Optional.of(error(id, INTERNAL_ERROR, "Internal error in '" + method + "': "
          + describe(e) + ". Details are in the FitNesse log.").toString());
    }
  }

  private JSONObject dispatch(Object id, String method, JSONObject params) {
    switch (method) {
      case "initialize":
        return result(id, initialize(params));
      case "ping":
        return result(id, new JSONObject());
      case "tools/list":
        return result(id, toolsList());
      case "tools/call":
        return toolsCall(id, params);
      default:
        return error(id, METHOD_NOT_FOUND, "Method not found: '" + method
            + "'. Supported methods: initialize, ping, tools/list, tools/call.");
    }
  }

  private JSONObject initialize(JSONObject params) {
    String requested = params.optString("protocolVersion", "");
    String version = SUPPORTED_VERSIONS.contains(requested) ? requested : SUPPORTED_VERSIONS.get(0);
    return new JSONObject()
        .put("protocolVersion", version)
        .put("capabilities", new JSONObject().put("tools", new JSONObject()))
        .put("serverInfo", new JSONObject().put("name", SERVER_NAME).put("version", serverVersion));
  }

  private JSONObject toolsList() {
    JSONArray tools = new JSONArray();
    for (Tool t : registry.all()) {
      tools.put(new JSONObject()
          .put("name", t.name())
          .put("description", t.description())
          .put("inputSchema", t.inputSchema()));
    }
    return new JSONObject().put("tools", tools);
  }

  private JSONObject toolsCall(Object id, JSONObject params) {
    String name = params.optString("name", "");
    Optional<Tool> found = registry.find(name);
    if (found.isEmpty()) {
      return error(id, INVALID_PARAMS, "Unknown tool '" + name + "'. Call tools/list to see the available tools.");
    }
    Tool tool = found.get();
    JSONObject args = params.optJSONObject("arguments");
    if (args == null) args = new JSONObject();
    Optional<String> violation = SchemaValidator.validate(tool.inputSchema(), args);
    if (violation.isPresent()) {
      return error(id, INVALID_PARAMS, "Invalid arguments for tool '" + name + "': " + violation.get());
    }
    ToolResult result;
    try {
      result = tool.call(args);
    } catch (ToolException e) {
      result = ToolResult.error(e.getMessage());
    } catch (Exception | LinkageError | StackOverflowError e) {
      LOG.log(Level.WARNING, "MCP tool '" + name + "' failed", e);
      result = ToolResult.error(describe(e));
    }
    return result(id, result.toJson());
  }

  /** "SimpleName: message", or just "SimpleName" when there is no message. */
  private static String describe(Throwable t) {
    String message = t.getMessage();
    return t.getClass().getSimpleName() + (message == null ? "" : ": " + message);
  }

  private static JSONObject result(Object id, JSONObject result) {
    return new JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result);
  }

  private static JSONObject error(Object id, int code, String message) {
    return new JSONObject().put("jsonrpc", "2.0").put("id", id)
        .put("error", new JSONObject().put("code", code).put("message", message));
  }
}
