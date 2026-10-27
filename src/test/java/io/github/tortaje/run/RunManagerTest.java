package io.github.tortaje.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fitnesse.wiki.PageData;
import fitnesse.wiki.WikiPage;
import fitnesse.wiki.WikiPageProperty;
import io.github.tortaje.protocol.ToolException;
import io.github.tortaje.testsupport.TestWiki;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunManagerTest {
  private static final Duration TIMEOUT = Duration.ofSeconds(60);
  private static final Set<RunState> FINISHED =
      EnumSet.of(RunState.COMPLETED, RunState.FAILED, RunState.CANCELLED);
  private static final String HEADER = TestWiki.slimHeader();
  private static final String GREEN =
      HEADER + "|Division|\n|numerator|denominator|quotient?|\n|10|2|5.0|\n";

  @TempDir Path tmp;
  TestWiki wiki;
  RunManager runs;

  @BeforeEach void setUp() {
    wiki = TestWiki.create(tmp);
    runs = new RunManager(wiki.context(), 20);
  }

  @AfterEach void tearDown() {
    runs.close();
    wiki.close();
  }

  private WikiPage testPage(String path, String content, String... tags) {
    return wiki.testPage(path, content, tags);
  }

  private static Run await(Run run) throws InterruptedException {
    long deadline = System.nanoTime() + TIMEOUT.toNanos();
    while (!FINISHED.contains(run.state())) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("Run " + run.id() + " still " + run.state() + " after " + TIMEOUT);
      }
      Thread.sleep(100);
    }
    return run;
  }

  @Test void greenPageCompletes() throws Exception {
    Run run = await(runs.submit(testPage("GreenTest", GREEN), null, null));

    assertThat(run.state()).isEqualTo(RunState.COMPLETED);
    assertThat(run.id()).matches("[0-9a-f]{8}");
    assertThat(run.target()).isEqualTo("GreenTest");
    assertThat(run.totalPages()).isEqualTo(1);
    assertThat(run.pagesDone()).isEqualTo(1);
    assertThat(run.totals().right()).isEqualTo(1);
    assertThat(run.totals().wrong()).isZero();
    assertThat(run.failureCause()).isEmpty();
    assertThat(run.elapsed()).isPositive();
    assertThat(run.pageResults()).hasSize(1);
    PageResult page = run.pageResults().get(0);
    assertThat(page.path()).isEqualTo("GreenTest");
    assertThat(page.failures()).isEmpty();
    assertThat(page.output()).contains("Division");
    assertThat(runs.find(run.id())).containsSame(run);
  }

  @Test void redPageRecordsAssertion() throws Exception {
    String red = HEADER + "|Division|\n|numerator|denominator|quotient?|\n|10|2|4.0|\n";
    Run run = await(runs.submit(testPage("RedTest", red), null, null));

    assertThat(run.state()).isEqualTo(RunState.COMPLETED);
    assertThat(run.totals().wrong()).isEqualTo(1);
    Failure failure = run.pageResults().get(0).failures().get(0);
    assertThat(failure.kind()).isEqualTo("assertion");
    assertThat(failure.expected()).isEqualTo("4.0");
    assertThat(failure.actual()).isEqualTo("5.0");
    assertThat(failure.instructionId()).isNotEmpty();
  }

  @Test void exceptionRecorded() throws Exception {
    String boom = HEADER + "|script|boom|\n|check|explode|true|\n";
    Run run = await(runs.submit(testPage("BoomTest", boom), null, null));

    assertThat(run.state()).isEqualTo(RunState.COMPLETED);
    assertThat(run.totals().exceptions()).isEqualTo(1);
    Failure failure = run.pageResults().get(0).failures().get(0);
    assertThat(failure.kind()).isEqualTo("exception");
    assertThat(failure.expected()).isNull();
    assertThat(failure.actual()).isNull();
    assertThat(failure.message()).contains("kaboom");
    assertThat(failure.message().split("\n", -1).length).isLessThanOrEqualTo(20);
  }

  @Test void suiteFilterSelectsTaggedPages() throws Exception {
    WikiPage suite = wiki.page("MySuite", "!contents");
    PageData data = suite.getData();
    data.getProperties().set("Suite");
    suite.commit(data);
    testPage("MySuite.SmokeTest", GREEN, "smoke");
    testPage("MySuite.OtherTest", GREEN);

    Run run = runs.submit(suite, "smoke", null);

    assertThat(run.totalPages()).isEqualTo(1);
    assertThat(run.target()).isEqualTo("MySuite");
    await(run);
    assertThat(run.pageResults()).extracting(PageResult::path).containsExactly("MySuite.SmokeTest");
  }

  @Test void noPagesIsToolException() {
    WikiPage page = wiki.page("JustText", "nothing to run");

    assertThatThrownBy(() -> runs.submit(page, null, null))
        .isInstanceOf(ToolException.class)
        .hasMessage("No test pages to run under 'JustText' with the given filters");
  }

  @Test void missingTestSystemExecutableFailsAndQueueContinues() throws Exception {
    String broken = HEADER.replace("!define TEST_SYSTEM {slim}",
        "!define TEST_SYSTEM {slim}\n!define COMMAND_PATTERN {/nonexistent/no-such-java -cp %p %m}")
        + "|Division|\n|numerator|denominator|quotient?|\n|10|2|5.0|\n";
    Run bad = await(runs.submit(testPage("BrokenTest", broken), null, null));

    assertThat(bad.state()).isEqualTo(RunState.FAILED);
    assertThat(bad.failureCause()).hasValueSatisfying(c -> assertThat(c).isNotBlank());

    Run good = await(runs.submit(testPage("GreenTest", GREEN), null, null));
    assertThat(good.state()).isEqualTo(RunState.COMPLETED);
    assertThat(good.totals().right()).isEqualTo(1);
  }

  @Test void cancelDuringTestSystemStartStopsBeforeAnyPageAndLeavesNoSlimProcess() throws Exception {
    Run run = runs.submit(testPage("SlowStartTest", GREEN), null, null);
    long deadline = System.nanoTime() + TIMEOUT.toNanos();
    while (run.state() == RunState.QUEUED && System.nanoTime() < deadline) Thread.sleep(1);
    // The worker has just started the run. Creating the runner takes a few ms, launching the Slim
    // JVM hundreds of ms: after a short pause the cancel lands in the JVM start-up window.
    Thread.sleep(50);
    assertThat(runs.cancel(run.id())).isTrue();

    await(run);
    assertThat(run.state()).isEqualTo(RunState.CANCELLED);
    assertThat(run.pagesDone()).isZero();
    assertThat(run.totals()).isEqualTo(Counts.ZERO);
    assertThat(run.pageResults()).isEmpty();
    assertNoChildProcessesWithin(Duration.ofSeconds(10));
  }

  private static void assertNoChildProcessesWithin(Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (ProcessHandle.current().children().anyMatch(ProcessHandle::isAlive)) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("Slim process still alive: "
            + ProcessHandle.current().children().map(h -> h.info().commandLine().orElse("?")).toList());
      }
      Thread.sleep(100);
    }
  }

  @Test void cancelQueuedRun() throws Exception {
    Run first = runs.submit(testPage("FirstTest", GREEN), null, null);
    Run second = runs.submit(testPage("SecondTest", GREEN), null, null);

    assertThat(second.state()).isEqualTo(RunState.QUEUED);
    assertThat(runs.cancel(second.id())).isTrue();
    assertThat(second.state()).isEqualTo(RunState.CANCELLED);

    await(first);
    assertThat(first.state()).isEqualTo(RunState.COMPLETED);
    // Give the worker a chance to (wrongly) pick up the cancelled run.
    Run third = await(runs.submit(testPage("ThirdTest", GREEN), null, null));
    assertThat(third.state()).isEqualTo(RunState.COMPLETED);
    assertThat(second.state()).isEqualTo(RunState.CANCELLED);
    assertThat(second.pagesDone()).isZero();
    assertThat(second.pageResults()).isEmpty();
    assertThat(second.elapsed()).isEqualTo(Duration.ZERO);
    assertThat(runs.cancel("nope1234")).isFalse();
    assertThat(runs.cancel(second.id())).isFalse();
  }

  @Test void evictsOldestFinishedRuns() throws Exception {
    try (RunManager small = new RunManager(wiki.context(), 2)) {
      WikiPage page = testPage("GreenTest", GREEN);
      Run first = await(small.submit(page, null, null));
      Run second = await(small.submit(page, null, null));
      Run third = await(small.submit(page, null, null));

      assertThat(small.find(first.id())).isEmpty();
      assertThat(small.find(second.id())).containsSame(second);
      assertThat(small.find(third.id())).containsSame(third);
    }
  }
}
