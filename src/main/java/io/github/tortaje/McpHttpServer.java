package io.github.tortaje;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import fitnesse.FitNesseContext;
import fitnesse.authentication.Authenticator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The MCP endpoint: a small HTTP listener of its own, on a dedicated port, serving
 * {@code POST /mcp}. FitNesse's own HTTP server cannot host it because it URL-decodes every
 * non-multipart POST body as form data before any responder runs, which rejects JSON containing
 * a lone {@code %} and reroutes bodies containing {@code &responder=}.
 *
 * <p>Configuration (FitNesse property, system property or environment variable, as resolved by
 * {@link FitNesseContext#getProperty}): {@code MCP_PORT} (default: FitNesse port + 1; {@code off}
 * disables the listener) and {@code MCP_HOST} (bind address, default {@code 127.0.0.1}).
 */
public final class McpHttpServer {
  private static final Logger LOG = Logger.getLogger(McpHttpServer.class.getName());

  static final String PATH = "/mcp";
  static final String PORT_PROPERTY = "MCP_PORT";
  static final String HOST_PROPERTY = "MCP_HOST";
  static final String DEFAULT_HOST = "127.0.0.1";
  static final String OFF = "off";
  /** A 10 MiB upload_file is about 13.4 MiB of base64, plus the JSON-RPC envelope. */
  static final int MAX_BODY_BYTES = 16 * 1024 * 1024;
  private static final int THREADS = 4;
  private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");
  private static final Map<FitNesseContext, McpHttpServer> RUNNING = new HashMap<>();

  private final FitNesseContext ctx;
  private final HttpServer server;
  private final ExecutorService executor;

  private McpHttpServer(FitNesseContext ctx, HttpServer server, ExecutorService executor) {
    this.ctx = ctx;
    this.server = server;
    this.executor = executor;
  }

  /**
   * Starts the listener for a context, unless it is disabled, FitNesse runs a single command
   * ({@code -c}) or only installs ({@code -i}). Never throws: a bad configuration or a port in use
   * is logged as a warning so that FitNesse itself still starts.
   */
  static Optional<McpHttpServer> startFor(FitNesseContext ctx) {
    if (ctx.getProperty("Command") != null || "true".equalsIgnoreCase(ctx.getProperty("InstallOnly"))) {
      return Optional.empty();
    }
    OptionalInt port;
    try {
      port = resolvePort(ctx.getProperty(PORT_PROPERTY), ctx.port);
    } catch (IllegalArgumentException e) {
      LOG.warning("MCP endpoint not started: " + e.getMessage());
      return Optional.empty();
    }
    if (port.isEmpty()) return Optional.empty();
    String host = ctx.getProperty(HOST_PROPERTY);
    if (host == null || host.isBlank()) host = DEFAULT_HOST;
    host = host.trim();

    ExecutorService executor = Executors.newFixedThreadPool(THREADS, daemonThreads());
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress(host, port.getAsInt()), 0);
      McpHttpServer started = new McpHttpServer(ctx, server, executor);
      server.createContext("/", started::handle);
      server.setExecutor(executor);
      startAsDaemon(server);
      synchronized (RUNNING) {
        RUNNING.put(ctx, started);
      }
      LOG.info("MCP endpoint: http://" + hostForUrl(host) + ":" + started.port() + PATH);
      return Optional.of(started);
    } catch (IOException | RuntimeException e) {
      executor.shutdownNow();
      LOG.warning("MCP endpoint not started: cannot listen on " + host + ":" + port.getAsInt()
          + " (" + e + "). Set " + PORT_PROPERTY + " to a free port (or " + OFF + "), or "
          + HOST_PROPERTY + " to another address, in plugins.properties or with -D" + PORT_PROPERTY + "=...");
      return Optional.empty();
    }
  }

  /**
   * The port to listen on: FitNesse's port + 1 when unset, empty for {@code off}.
   *
   * @throws IllegalArgumentException for anything that is not a port number or {@code off}
   */
  static OptionalInt resolvePort(String value, int fitnessePort) {
    if (value == null || value.isBlank()) return OptionalInt.of(fitnessePort + 1);
    String v = value.trim();
    if (v.equalsIgnoreCase(OFF)) return OptionalInt.empty();
    try {
      int port = Integer.parseInt(v);
      if (port >= 0 && port <= 65535) return OptionalInt.of(port);
    } catch (NumberFormatException e) {
      // reported below
    }
    throw new IllegalArgumentException(PORT_PROPERTY + " must be a port number (0-65535) or '"
        + OFF + "', not '" + value + "'");
  }

  /** The listener started for a context, if any. */
  public static Optional<McpHttpServer> running(FitNesseContext ctx) {
    synchronized (RUNNING) {
      return Optional.ofNullable(RUNNING.get(ctx));
    }
  }

  public int port() { return server.getAddress().getPort(); }

  /** Stops listening and releases the port and the worker threads. */
  public void stop() {
    synchronized (RUNNING) {
      RUNNING.remove(ctx, this);
    }
    server.stop(0);
    executor.shutdownNow();
  }

  // ---- request handling ---------------------------------------------------------------------

  private void handle(HttpExchange exchange) {
    try {
      respond(exchange);
    } catch (Throwable t) {
      LOG.log(Level.WARNING, "MCP request failed unexpectedly", t);
      try {
        send(exchange, 500, "application/json; charset=utf-8",
            "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"Internal error: "
                + t.getClass().getSimpleName() + "; see the FitNesse log\"}}");
      } catch (Throwable ignored) {
        // headers already sent or connection gone: nothing more can be done
      }
    } finally {
      exchange.close();
    }
  }

  private void respond(HttpExchange exchange) throws IOException {
    if (!originAllowed(exchange.getRequestHeaders().getFirst("Origin"))) {
      sendText(exchange, 403, "Forbidden: the Origin header must be absent or point to localhost, "
          + "127.0.0.1 or [::1].");
      return;
    }
    if (!PATH.equals(exchange.getRequestURI().getPath())) {
      sendText(exchange, 404, "Not found: the MCP endpoint is " + PATH + ".");
      return;
    }
    if (!"POST".equals(exchange.getRequestMethod())) {
      exchange.getResponseHeaders().set("Allow", "POST");
      sendText(exchange, 405, "Method not allowed: the MCP endpoint only accepts POST with a JSON-RPC body.");
      return;
    }
    if (!authenticated(exchange.getRequestHeaders().getFirst("Authorization"))) {
      exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"FitNesse MCP\"");
      sendText(exchange, 401, "Unauthorized: send HTTP Basic credentials of a FitNesse user.");
      return;
    }
    Optional<String> body = readBody(exchange);
    if (body.isEmpty()) {
      sendText(exchange, 413, "Payload too large: the request body is limited to "
          + MAX_BODY_BYTES + " bytes.");
      return;
    }
    Optional<String> reply = McpRuntime.forContext(ctx).protocol().handle(body.get());
    if (reply.isEmpty()) {
      exchange.sendResponseHeaders(202, -1);
    } else {
      send(exchange, 200, "application/json; charset=utf-8", reply.get());
    }
  }

  private static boolean originAllowed(String origin) {
    if (origin == null) return true;
    try {
      String host = new URI(origin.trim()).getHost();
      return host != null && LOCAL_HOSTS.contains(host.toLowerCase(Locale.ROOT));
    } catch (URISyntaxException e) {
      return false;
    }
  }

  private boolean authenticated(String authorization) {
    String user = null;
    String password = null;
    if (authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
      try {
        String decoded = new String(Base64.getDecoder().decode(authorization.substring(6).trim()),
            StandardCharsets.UTF_8);
        int colon = decoded.indexOf(':');
        user = colon < 0 ? decoded : decoded.substring(0, colon);
        password = colon < 0 ? "" : decoded.substring(colon + 1);
      } catch (IllegalArgumentException e) {
        user = null; // malformed base64: treated as no credentials
      }
    }
    Authenticator authenticator = ctx.authenticator;
    return authenticator == null || authenticator.isAuthenticated(user, password);
  }

  /** The body as UTF-8, or empty when it exceeds {@link #MAX_BODY_BYTES}. */
  private static Optional<String> readBody(HttpExchange exchange) throws IOException {
    String length = exchange.getRequestHeaders().getFirst("Content-Length");
    if (length != null) {
      try {
        if (Long.parseLong(length.trim()) > MAX_BODY_BYTES) return Optional.empty();
      } catch (NumberFormatException e) {
        // let the server's own framing decide; the cap below still applies
      }
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    try (InputStream in = exchange.getRequestBody()) {
      for (int n; (n = in.read(buffer)) != -1; ) {
        if (out.size() + n > MAX_BODY_BYTES) return Optional.empty();
        out.write(buffer, 0, n);
      }
    }
    return Optional.of(out.toString(StandardCharsets.UTF_8));
  }

  private static void sendText(HttpExchange exchange, int status, String text) throws IOException {
    send(exchange, status, "text/plain; charset=utf-8", text);
  }

  private static void send(HttpExchange exchange, int status, String contentType, String text)
      throws IOException {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", contentType);
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
  }

  // ---- threads ------------------------------------------------------------------------------

  private static java.util.concurrent.ThreadFactory daemonThreads() {
    AtomicInteger count = new AtomicInteger();
    return r -> {
      Thread t = new Thread(r, "mcp-http-" + count.incrementAndGet());
      t.setDaemon(true);
      return t;
    };
  }

  /**
   * The JDK's dispatcher thread inherits the daemon flag of the thread that calls
   * {@link HttpServer#start()}; starting from a daemon thread keeps the listener from holding the
   * JVM alive after FitNesse stops.
   */
  private static void startAsDaemon(HttpServer server) {
    Thread starter = new Thread(server::start, "mcp-http-start");
    starter.setDaemon(true);
    starter.start();
    try {
      starter.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static String hostForUrl(String host) {
    return host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
  }
}
