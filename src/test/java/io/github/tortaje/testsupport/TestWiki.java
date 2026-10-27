package io.github.tortaje.testsupport;

import fitnesse.ContextConfigurator;
import fitnesse.FitNesseContext;
import fitnesse.authentication.Authenticator;
import fitnesse.wiki.PageData;
import fitnesse.wiki.PathParser;
import fitnesse.wiki.WikiPage;
import fitnesse.wiki.WikiPageProperty;
import fitnesse.wiki.WikiPageUtil;
import io.github.tortaje.McpHttpServer;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A throw-away FitNesse context rooted in a temporary directory. The MCP HTTP listener is off
 * ({@code MCP_PORT=off}) unless {@link #withMcpPort(int)} is used, so unit tests open no socket.
 */
public final class TestWiki implements AutoCloseable {
  private final Path tempDir;
  private final Authenticator authenticator;
  private final Map<String, String> parameters;
  private final FitNesseContext context;

  private TestWiki(Path tempDir, Authenticator authenticator, Map<String, String> parameters) {
    try {
      this.tempDir = tempDir;
      this.authenticator = authenticator;
      this.parameters = parameters;
      ContextConfigurator configurator = ContextConfigurator.systemDefaults()
          .withRootPath(tempDir.toString())
          .withPort(freePort());
      parameters.forEach(configurator::withParameter);
      if (authenticator != null) configurator = configurator.withAuthenticator(authenticator);
      this.context = configurator.makeFitNesseContext();
    } catch (Exception e) {
      throw new IllegalStateException("Cannot create test wiki in " + tempDir, e);
    }
  }

  public static TestWiki create(Path tempDir) {
    Map<String, String> parameters = new LinkedHashMap<>();
    parameters.put("MCP_PORT", "off");
    return new TestWiki(tempDir, null, parameters);
  }

  /** A new wiki on the same directory whose requests are checked by the given authenticator. */
  public TestWiki withAuthenticator(Authenticator authenticator) {
    return new TestWiki(tempDir, authenticator, parameters);
  }

  /** A new wiki on the same directory with one more configuration parameter. */
  public TestWiki withParameter(String key, String value) {
    Map<String, String> more = new LinkedHashMap<>(parameters);
    more.put(key, value);
    return new TestWiki(tempDir, authenticator, more);
  }

  /** A new wiki on the same directory whose plugin serves MCP on the given port. */
  public TestWiki withMcpPort(int port) {
    return withParameter("MCP_PORT", Integer.toString(port));
  }

  public FitNesseContext context() { return context; }

  public WikiPage root() { return context.getRootPage(); }

  public WikiPage page(String path, String content) {
    return WikiPageUtil.addPage(root(), PathParser.parse(path), content);
  }

  /** Adds a page flagged as a test page, optionally tagged with suite tags. */
  public WikiPage testPage(String path, String content, String... tags) {
    WikiPage page = page(path, content);
    PageData data = page.getData();
    data.getProperties().set("Test");
    if (tags.length > 0) data.getProperties().set(WikiPageProperty.SUITES, String.join(",", tags));
    page.commit(data);
    return page;
  }

  /** Slim test header: class path, slim test system and the {@code demo} import table. */
  public static String slimHeader() {
    return classpathHeader() + "\n!define TEST_SYSTEM {slim}\n\n|import|\n|demo|\n\n";
  }

  /** One {@code !path <entry>} line per entry of the current JVM class path. */
  public static String classpathHeader() {
    return Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
        .filter(e -> !e.isEmpty())
        .map(e -> "!path " + e)
        .collect(Collectors.joining("\n"));
  }

  /** Stops the MCP listener of this wiki's context, if one was started. */
  @Override public void close() {
    McpHttpServer.running(context).ifPresent(McpHttpServer::stop);
  }

  public static int freePort() throws IOException {
    try (ServerSocket s = new ServerSocket(0)) {
      return s.getLocalPort();
    }
  }
}
