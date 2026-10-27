package io.github.tortaje.protocol;

import java.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

/** Result of a tool call: MCP content items plus the error flag. */
public record ToolResult(JSONArray content, boolean isError) {

  /** A single text item holding the JSON serialisation of the given object or array. */
  public static ToolResult json(Object jsonObjectOrArray) {
    return text(String.valueOf(jsonObjectOrArray), false);
  }

  /** A single base64 image item. */
  public static ToolResult image(byte[] data, String mimeType) {
    JSONObject item = new JSONObject()
        .put("type", "image")
        .put("data", Base64.getEncoder().encodeToString(data))
        .put("mimeType", mimeType);
    return new ToolResult(new JSONArray().put(item), false);
  }

  /** A single text item with the given message, flagged as an error. */
  public static ToolResult error(String message) {
    return text(message, true);
  }

  private static ToolResult text(String text, boolean isError) {
    JSONObject item = new JSONObject().put("type", "text").put("text", text);
    return new ToolResult(new JSONArray().put(item), isError);
  }

  public JSONObject toJson() {
    return new JSONObject().put("content", content).put("isError", isError);
  }
}
