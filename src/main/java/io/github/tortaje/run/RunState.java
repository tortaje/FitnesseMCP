package io.github.tortaje.run;

/** Lifecycle of a run; MCP JSON uses the lower-case names. */
public enum RunState {
  QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED;

  public boolean isFinished() {
    return this == COMPLETED || this == FAILED || this == CANCELLED;
  }
}
