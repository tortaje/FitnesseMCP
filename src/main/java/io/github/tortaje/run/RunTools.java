package io.github.tortaje.run;

import fitnesse.FitNesseContext;
import io.github.tortaje.protocol.Tool;
import io.github.tortaje.protocol.ToolException;
import io.github.tortaje.protocol.ToolRegistry;
import io.github.tortaje.protocol.ToolResult;
import io.github.tortaje.wiki.PagePaths;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Run tools: run_tests, get_run_status, get_run_results and cancel_run. */
public final class RunTools {
  private RunTools() {}

  public static void registerAll(ToolRegistry r, FitNesseContext ctx, RunManager runs) {
    r.register(new RunTests(ctx, runs));
    r.register(new GetRunStatus(runs));
    r.register(new GetRunResults(runs));
    r.register(new CancelRun(runs));
  }

  private static JSONObject runIdSchema(String extraName, JSONObject extra) {
    JSONObject props = new JSONObject().put("runId", new JSONObject().put("type", "string")
        .put("description", "Run id returned by run_tests."));
    if (extraName != null) props.put(extraName, extra);
    return new JSONObject().put("type", "object").put("properties", props)
        .put("required", new JSONArray().put("runId"));
  }

  private static Run findRun(RunManager runs, String runId) throws ToolException {
    return runs.find(runId).orElseThrow(() -> new ToolException("No run with id '" + runId
        + "'; ids come from run_tests, and only the 20 most recent finished runs are kept"));
  }

  private static String stateName(RunState state) {
    return state.name().toLowerCase(Locale.ROOT);
  }

  private static JSONObject countsJson(Counts c) {
    return new JSONObject().put("right", c.right()).put("wrong", c.wrong())
        .put("ignores", c.ignores()).put("exceptions", c.exceptions());
  }

  private static JSONObject failureJson(Failure f) {
    return new JSONObject()
        .put("kind", f.kind())
        .put("instructionId", nullable(f.instructionId()))
        .put("expected", nullable(f.expected()))
        .put("actual", nullable(f.actual()))
        .put("message", nullable(f.message()));
  }

  private static Object nullable(String s) {
    return s == null ? JSONObject.NULL : s;
  }

  /**
   * One page of get_run_results. The output is left out with onlyFailures, except when the page
   * has errors whose text could not be extracted: then the output is the only explanation.
   */
  static JSONObject pageJson(PageResult p, boolean onlyFailures) {
    JSONArray failures = new JSONArray();
    p.failures().forEach(f -> failures.put(failureJson(f)));
    JSONObject page = new JSONObject().put("path", p.path())
        .put("counts", countsJson(p.counts())).put("failures", failures);
    if (!onlyFailures || p.failures().stream().anyMatch(Failure::isUnexplainedTableError)) {
      page.put("output", p.output());
    }
    return page;
  }

  private static final class RunTests implements Tool {
    private final FitNesseContext ctx;
    private final RunManager runs;

    RunTests(FitNesseContext ctx, RunManager runs) {
      this.ctx = ctx;
      this.runs = runs;
    }

    public String name() { return "run_tests"; }

    public String description() {
      return "Starts an asynchronous run of a test page, or of every test page under a suite page, "
          + "and returns {runId, totalPages} at once. Poll get_run_status, then call get_run_results. "
          + "Runs execute one at a time; later runs wait in a queue. Runs are not written to FitNesse's test history.";
    }

    public JSONObject inputSchema() {
      JSONObject props = new JSONObject()
          .put("path", new JSONObject().put("type", "string").put("description",
              "Test or suite page as dot-separated WikiWords, e.g. FrontPage.MySuite."))
          .put("suiteFilter", new JSONObject().put("type", "string").put("description",
              "Only run pages carrying one of these comma-separated suite tags."))
          .put("excludeSuiteFilter", new JSONObject().put("type", "string").put("description",
              "Skip pages carrying one of these comma-separated suite tags."));
      return new JSONObject().put("type", "object").put("properties", props)
          .put("required", new JSONArray().put("path"));
    }

    public ToolResult call(JSONObject args) throws ToolException {
      var page = PagePaths.resolve(ctx.getRootPage(), args.getString("path"));
      Run run = runs.submit(page, args.optString("suiteFilter", null), args.optString("excludeSuiteFilter", null));
      return ToolResult.json(new JSONObject().put("runId", run.id()).put("totalPages", run.totalPages()));
    }
  }

  private static final class GetRunStatus implements Tool {
    private final RunManager runs;

    GetRunStatus(RunManager runs) { this.runs = runs; }

    public String name() { return "get_run_status"; }

    public String description() {
      return "Returns the progress of a run: state (queued, running, completed, failed or cancelled), "
          + "pagesDone, totalPages, assertion counts so far, elapsedMs and, for a failed run, failureCause. "
          + "Poll until the state is completed, failed or cancelled.";
    }

    public JSONObject inputSchema() { return runIdSchema(null, null); }

    public ToolResult call(JSONObject args) throws ToolException {
      // One snapshot, so the counters, pagesDone and state all belong to the same instant.
      Run.Status s = findRun(runs, args.getString("runId")).snapshot();
      JSONObject out = new JSONObject()
          .put("runId", s.id())
          .put("state", stateName(s.state()))
          .put("pagesDone", s.pagesDone())
          .put("totalPages", s.totalPages())
          .put("counts", countsJson(s.totals()))
          .put("elapsedMs", s.elapsed().toMillis());
      // A cancelled run usually carries the Slim error caused by the kill; that is noise.
      if (s.state() == RunState.FAILED) s.failureCause().ifPresent(c -> out.put("failureCause", c));
      return ToolResult.json(out);
    }
  }

  private static final class GetRunResults implements Tool {
    private final RunManager runs;

    GetRunResults(RunManager runs) { this.runs = runs; }

    public String name() { return "get_run_results"; }

    public String description() {
      return "Returns the per-page results of a run: counts, failures (kind, instructionId, expected, "
          + "actual, message) and the page's HTML output. kind is 'assertion' (a wrong check), 'exception' "
          + "(a fixture or Slim exception) or 'table' (a table-level error such as 'Bad table!', taken from "
          + "the output; no instructionId). By default (onlyFailures=true) only pages with "
          + "wrong or exception assertions are listed and output is left out, except for a page whose error "
          + "text could not be extracted (message 'Table-level error; see output'); pass onlyFailures=false "
          + "for all pages with output. "
          + "Results of a running run contain the pages finished so far.";
    }

    public JSONObject inputSchema() {
      return runIdSchema("onlyFailures", new JSONObject().put("type", "boolean")
          .put("description", "Default true: list only failing pages, without output."));
    }

    public ToolResult call(JSONObject args) throws ToolException {
      Run run = findRun(runs, args.getString("runId"));
      boolean onlyFailures = args.optBoolean("onlyFailures", true);
      Run.Status status = run.snapshot();
      JSONArray pages = new JSONArray();
      for (PageResult p : run.pageResults()) {
        if (onlyFailures && p.counts().wrong() + p.counts().exceptions() == 0) continue;
        pages.put(pageJson(p, onlyFailures));
      }
      return ToolResult.json(new JSONObject().put("runId", status.id())
          .put("state", stateName(status.state())).put("pages", pages));
    }
  }

  private static final class CancelRun implements Tool {
    private final RunManager runs;

    CancelRun(RunManager runs) { this.runs = runs; }

    public String name() { return "cancel_run"; }

    public String description() {
      return "Cancels a queued or running run. A queued run never starts; a running one has its test "
          + "system stopped. Returns {runId, state:\"cancelled\"}; a running run may take a moment to settle, "
          + "so confirm with get_run_status. Fails when the run has already finished.";
    }

    public JSONObject inputSchema() { return runIdSchema(null, null); }

    public ToolResult call(JSONObject args) throws ToolException {
      String runId = args.getString("runId");
      Run run = findRun(runs, runId);
      if (!runs.cancel(runId)) {
        throw new ToolException("Run '" + runId + "' has already finished (" + stateName(run.state())
            + ") and cannot be cancelled");
      }
      return ToolResult.json(new JSONObject().put("runId", runId).put("state", "cancelled"));
    }
  }
}
