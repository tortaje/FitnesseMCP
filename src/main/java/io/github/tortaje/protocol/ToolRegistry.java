package io.github.tortaje.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Name-to-tool lookup that preserves registration order. */
public class ToolRegistry {
  private final Map<String, Tool> tools = new LinkedHashMap<>();

  public synchronized void register(Tool t) {
    if (tools.containsKey(t.name())) {
      throw new IllegalArgumentException("Tool already registered: " + t.name());
    }
    tools.put(t.name(), t);
  }

  public synchronized Optional<Tool> find(String name) {
    return Optional.ofNullable(tools.get(name));
  }

  public synchronized List<Tool> all() {
    return new ArrayList<>(tools.values());
  }
}
