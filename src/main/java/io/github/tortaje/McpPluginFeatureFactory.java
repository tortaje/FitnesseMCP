package io.github.tortaje;

import fitnesse.plugins.PluginFeatureFactory;
import fitnesse.testrunner.run.TestRunFactoryRegistry;

/**
 * Starts the MCP endpoint ({@link McpHttpServer}) for each FitNesse context. FitNesse offers no
 * plugin hook for "context ready"; {@code registerTestRunFactories} is the one that receives the
 * fully built context (through {@link TestRunFactoryRegistry#getContext()}). No test-run factory
 * is registered.
 */
public class McpPluginFeatureFactory implements PluginFeatureFactory {
  @Override
  public void registerTestRunFactories(TestRunFactoryRegistry registry) {
    McpHttpServer.startFor(registry.getContext());
  }
}
