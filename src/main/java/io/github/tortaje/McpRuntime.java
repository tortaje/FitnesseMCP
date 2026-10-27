package io.github.tortaje;

import fitnesse.FitNesseContext;
import io.github.tortaje.files.FileTools;
import io.github.tortaje.fixtures.FixtureTools;
import io.github.tortaje.protocol.McpProtocol;
import io.github.tortaje.protocol.ToolRegistry;
import io.github.tortaje.run.RunManager;
import io.github.tortaje.run.RunTools;
import io.github.tortaje.wiki.WikiReadTools;
import io.github.tortaje.wiki.WikiWriteTools;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * State shared by all requests of one FitNesse context: the tool registry, the protocol and the
 * run manager live here, one instance per context.
 */
final class McpRuntime {
  private static final Map<FitNesseContext, McpRuntime> RUNTIMES = new WeakHashMap<>();
  private static final int MAX_COMPLETED_RUNS = 20;

  private final ToolRegistry registry = new ToolRegistry();
  private final McpProtocol protocol;
  private final RunManager runs;

  private McpRuntime(FitNesseContext ctx) {
    runs = new RunManager(ctx, MAX_COMPLETED_RUNS);
    WikiReadTools.registerAll(registry, ctx);
    WikiWriteTools.registerAll(registry, ctx);
    FileTools.registerAll(registry, ctx);
    RunTools.registerAll(registry, ctx, runs);
    FixtureTools.registerAll(registry, ctx);
    String version = McpRuntime.class.getPackage().getImplementationVersion();
    protocol = new McpProtocol(registry, version != null ? version : "dev");
  }

  static McpRuntime forContext(FitNesseContext ctx) {
    synchronized (RUNTIMES) {
      return RUNTIMES.computeIfAbsent(ctx, McpRuntime::new);
    }
  }

  ToolRegistry registry() { return registry; }

  McpProtocol protocol() { return protocol; }

  RunManager runs() { return runs; }
}
