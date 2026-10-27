package io.github.tortaje.protocol;

/** A business error raised by a tool; its message is shown to the MCP client as-is. */
public class ToolException extends Exception {
  private static final long serialVersionUID = 1L;

  public ToolException(String message) {
    super(message);
  }
}
