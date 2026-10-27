package io.github.tortaje.protocol;

import org.json.JSONObject;

/** An MCP tool: a named, schema-described operation callable through {@code tools/call}. */
public interface Tool {
  String name();

  String description();

  /** JSON Schema (draft 2020-12 subset) describing the tool arguments. */
  JSONObject inputSchema();

  /**
   * Executes the tool. Business errors are reported with {@link ToolException}; they reach the
   * client as a tool result with {@code isError=true}.
   */
  ToolResult call(JSONObject args) throws ToolException;
}
