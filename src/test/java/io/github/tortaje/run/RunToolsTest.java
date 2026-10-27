package io.github.tortaje.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tortaje.protocol.McpProtocol;
import io.github.tortaje.protocol.ToolRegistry;
import io.github.tortaje.testsupport.McpClient;
import io.github.tortaje.testsupport.TestWiki;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunToolsTest {
  private static final String HEADER = TestWiki.slimHeader();
  private static final String GREEN =
      HEADER + "|Division|\n|numerator|denominator|quotient?|\n|10|2|5.0|\n";
  private static final String RED =
      HEADER + "|Division|\n|numerator|denominator|quotient?|\n|10|2|4.0|\n";

  @TempDir Path tmp;
  TestWiki wiki;
  RunManager runs;
  McpProtocol protocol;
  McpClient client;

  @BeforeEach void setUp() {
    wiki = TestWiki.create(tmp);
    runs = new RunManager(wiki.context(), 20);
    ToolRegistry registry = new ToolRegistry();
    RunTools.registerAll(registry, wiki.context(), runs);
    protocol = new McpProtocol(registry, "test");
    client = new McpClient(protocol);
  }

  @AfterEach void tearDown() {
    runs.close();
    wiki.close();
  }

  private JSONObject call(String tool, JSONObject args) {
    return client.json(client.callTool(tool, args));
  }

  private String awaitFinished(String runId) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (System.nanoTime() < deadline) {
      String state = call("get_run_status", new JSONObject().put("runId", runId)).getString("state");
      if (!state.equals("queued") && !state.equals("running")) return state;
      Thread.sleep(100);
    }
    throw new AssertionError("Run " + runId + " did not finish in time");
  }

  private void makeSuite() {
    var suite = wiki.page("MySuite", "!contents");
    var data = suite.getData();
    data.getProperties().set("Suite");
    suite.commit(data);
  }

  @Test void runThenPollThenResults() throws Exception {
    wiki.testPage("RedTest", RED);

    JSONObject started = call("run_tests", new JSONObject().put("path", "RedTest"));
    String runId = started.getString("runId");
    assertThat(started.getInt("totalPages")).isEqualTo(1);

    assertThat(awaitFinished(runId)).isEqualTo("completed");
    JSONObject status = call("get_run_status", new JSONObject().put("runId", runId));
    assertThat(status.getInt("pagesDone")).isEqualTo(1);
    assertThat(status.getInt("totalPages")).isEqualTo(1);
    assertThat(status.getJSONObject("counts").getInt("wrong")).isEqualTo(1);
    assertThat(status.has("elapsedMs")).isTrue();
    assertThat(status.has("failureCause")).isFalse();

    JSONObject results = call("get_run_results", new JSONObject().put("runId", runId));
    assertThat(results.getString("state")).isEqualTo("completed");
    JSONArray pages = results.getJSONArray("pages");
    assertThat(pages.length()).isEqualTo(1);
    JSONObject page = pages.getJSONObject(0);
    assertThat(page.getString("path")).isEqualTo("RedTest");
    assertThat(page.has("output")).isFalse();
    assertThat(page.getJSONObject("counts").getInt("wrong")).isEqualTo(1);
    JSONObject failure = page.getJSONArray("failures").getJSONObject(0);
    assertThat(failure.getString("kind")).isEqualTo("assertion");
    assertThat(failure.getString("expected")).isEqualTo("4.0");
    assertThat(failure.getString("actual")).isEqualTo("5.0");
  }

  @Test void onlyFailuresFalseIncludesGreenPagesAndOutput() throws Exception {
    makeSuite();
    wiki.testPage("MySuite.GreenTest", GREEN);
    wiki.testPage("MySuite.RedTest", RED);

    String runId = call("run_tests", new JSONObject().put("path", "MySuite")).getString("runId");
    assertThat(awaitFinished(runId)).isEqualTo("completed");

    JSONArray onlyRed = call("get_run_results", new JSONObject().put("runId", runId)).getJSONArray("pages");
    assertThat(onlyRed.length()).isEqualTo(1);
    assertThat(onlyRed.getJSONObject(0).getString("path")).isEqualTo("MySuite.RedTest");

    JSONArray all = call("get_run_results",
        new JSONObject().put("runId", runId).put("onlyFailures", false)).getJSONArray("pages");
    assertThat(all.length()).isEqualTo(2);
    for (int i = 0; i < all.length(); i++) {
      assertThat(all.getJSONObject(i).getString("output")).contains("Division");
    }
  }

  @Test void suiteFilterIsApplied() throws Exception {
    makeSuite();
    wiki.testPage("MySuite.SmokeTest", GREEN, "smoke");
    wiki.testPage("MySuite.OtherTest", GREEN);

    JSONObject started = call("run_tests",
        new JSONObject().put("path", "MySuite").put("suiteFilter", "smoke"));

    assertThat(started.getInt("totalPages")).isEqualTo(1);
    assertThat(awaitFinished(started.getString("runId"))).isEqualTo("completed");
  }

  @Test void noMatchingPagesIsError() {
    wiki.page("JustText", "nothing to run");

    String error = client.errorText(client.callTool("run_tests", new JSONObject().put("path", "JustText")));

    assertThat(error).contains("No test pages to run under 'JustText'");
  }

  @Test void unknownRunIdIsError() {
    for (String tool : List.of("get_run_status", "get_run_results", "cancel_run")) {
      String error = client.errorText(client.callTool(tool, new JSONObject().put("runId", "nope")));
      assertThat(error).as(tool).contains("nope");
    }
  }

  @Test void missingPageIsError() {
    String error = client.errorText(client.callTool("run_tests", new JSONObject().put("path", "No.Page")));

    assertThat(error).contains("No.Page").contains("closest existing ancestor");
  }

  @Test void cancelRun() throws Exception {
    makeSuite();
    for (int i = 1; i <= 3; i++) wiki.testPage("MySuite.Page" + i + "Test", GREEN);
    String runId = call("run_tests", new JSONObject().put("path", "MySuite")).getString("runId");

    JSONObject cancelled = call("cancel_run", new JSONObject().put("runId", runId));

    assertThat(cancelled.getString("runId")).isEqualTo(runId);
    assertThat(cancelled.getString("state")).isEqualTo("cancelled");
    assertThat(awaitFinished(runId)).isEqualTo("cancelled");
    JSONObject status = call("get_run_status", new JSONObject().put("runId", runId));
    assertThat(status.has("failureCause")).isFalse();
  }

  @Test void cancelOfFinishedRunIsError() throws Exception {
    wiki.testPage("GreenTest", GREEN);
    String runId = call("run_tests", new JSONObject().put("path", "GreenTest")).getString("runId");
    assertThat(awaitFinished(runId)).isEqualTo("completed");

    String error = client.errorText(client.callTool("cancel_run", new JSONObject().put("runId", runId)));

    assertThat(error).contains(runId).contains("completed");
  }

  @Test void failedRunReportsFailureCause() throws Exception {
    String broken = GREEN.replace("!define TEST_SYSTEM {slim}",
        "!define TEST_SYSTEM {slim}\n!define COMMAND_PATTERN {/nonexistent/no-such-java -cp %p %m}");
    wiki.testPage("BrokenTest", broken);
    String runId = call("run_tests", new JSONObject().put("path", "BrokenTest")).getString("runId");

    assertThat(awaitFinished(runId)).isEqualTo("failed");

    JSONObject status = call("get_run_status", new JSONObject().put("runId", runId));
    assertThat(status.getString("failureCause")).isNotBlank();
  }

  @Test void statusIsSafeUnderConcurrentPolling() throws Exception {
    makeSuite();
    for (int i = 1; i <= 3; i++) wiki.testPage("MySuite.Page" + i + "Test", GREEN);
    String runId = call("run_tests", new JSONObject().put("path", "MySuite")).getString("runId");

    int threads = 8;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<Void>> futures = new ArrayList<>();
      for (int t = 0; t < threads; t++) {
        futures.add(pool.submit(() -> {
          McpClient own = new McpClient(protocol);
          int lastDone = 0;
          for (int i = 0; i < 50; i++) {
            JSONObject status = own.json(own.callTool("get_run_status", new JSONObject().put("runId", runId)));
            int done = status.getInt("pagesDone");
            JSONObject c = status.getJSONObject("counts");
            assertThat(done).isGreaterThanOrEqualTo(lastDone);
            // Every page of this suite has exactly one passing assertion and nothing else.
            assertThat(c.getInt("right")).isEqualTo(done);
            assertThat(c.getInt("wrong") + c.getInt("exceptions") + c.getInt("ignores")).isZero();
            lastDone = done;
            Thread.sleep(5);
          }
          return null;
        }));
      }
      for (Future<Void> f : futures) f.get(60, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
    assertThat(awaitFinished(runId)).isEqualTo("completed");
  }

  private JSONObject onlyFailingPage(String content) throws Exception {
    wiki.testPage("BadTableTest", content);
    String runId = call("run_tests", new JSONObject().put("path", "BadTableTest")).getString("runId");
    assertThat(awaitFinished(runId)).isEqualTo("completed");
    JSONArray pages = call("get_run_results", new JSONObject().put("runId", runId)).getJSONArray("pages");
    assertThat(pages.length()).isEqualTo(1);
    return pages.getJSONObject(0);
  }

  @Test void badScriptTableIsReportedAsTableFailure() throws Exception {
    JSONObject page = onlyFailingPage(HEADER + "|script|\n|check|\n");
    assertThat(page.getJSONObject("counts").getInt("exceptions")).isEqualTo(1);
    JSONArray failures = page.getJSONArray("failures");
    assertThat(failures.length()).as(page.toString()).isEqualTo(1);
    JSONObject failure = failures.getJSONObject(0);
    assertThat(failure.getString("kind")).isEqualTo("table");
    assertThat(failure.getString("message"))
        .isEqualTo("script: Bad table! Too few columns in row 2. Expected a function in column 2.");
    assertThat(failure.isNull("instructionId")).isTrue();
    assertThat(page.has("output")).isFalse();
  }

  @Test void scenarioWithoutNameIsReportedAsTableFailure() throws Exception {
    JSONObject page = onlyFailingPage(HEADER + "|scenario|\n");
    JSONObject failure = page.getJSONArray("failures").getJSONObject(0);
    assertThat(failure.getString("kind")).isEqualTo("table");
    assertThat(failure.getString("message")).isEqualTo("scenario: Bad table! Scenario tables must have a name.");
  }

  /** Here Slim reports real exceptions, which already explain the counts: no table failure is added. */
  @Test void unknownQueryFixtureKeepsItsExceptionFailures() throws Exception {
    JSONObject page = onlyFailingPage(HEADER + "|query:Nope|\n|x|\n");
    JSONArray failures = page.getJSONArray("failures");
    assertThat(failures.length()).isEqualTo(page.getJSONObject("counts").getInt("exceptions"));
    for (int i = 0; i < failures.length(); i++) {
      assertThat(failures.getJSONObject(i).getString("kind")).isEqualTo("exception");
    }
  }

  @Test void unexplainedTableErrorKeepsOutputEvenWithOnlyFailures() {
    PageResult unexplained = new PageResult("P", new Counts(0, 0, 0, 1),
        List.of(Failure.unexplainedTableError()), "<table>...</table>");
    assertThat(RunTools.pageJson(unexplained, true).getString("output")).isEqualTo("<table>...</table>");

    PageResult explained = new PageResult("P", new Counts(0, 0, 0, 1),
        List.of(new Failure(Failure.TABLE, null, null, null, "script: Bad table!")), "<table>...</table>");
    assertThat(RunTools.pageJson(explained, true).has("output")).isFalse();
    assertThat(RunTools.pageJson(explained, false).getString("output")).isEqualTo("<table>...</table>");
  }
}
