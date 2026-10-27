package io.github.tortaje.run;

import fitnesse.FitNesseContext;
import fitnesse.testrunner.MultipleTestsRunner;
import fitnesse.testrunner.SuiteContentsFinder;
import fitnesse.testrunner.SuiteFilter;
import fitnesse.testrunner.run.TestRun;
import fitnesse.wiki.WikiPage;
import io.github.tortaje.protocol.ToolException;
import io.github.tortaje.wiki.PagePaths;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Queues test runs and executes them one at a time on a single daemon worker thread (FitNesse
 * test systems are not designed to run concurrently in one server). Keeps the most recent
 * finished runs so their results can be fetched.
 *
 * <p>Locking: the manager's own monitor guards the run table, the finished-order queue and the
 * current runner and collector; each {@link Run} guards its own state. The manager never holds
 * its monitor while calling into FitNesse.
 */
public final class RunManager implements AutoCloseable {
  private final FitNesseContext ctx;
  private final int maxCompletedRuns;
  private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
    Thread thread = new Thread(task, "fitnesse-mcp-run-worker");
    thread.setDaemon(true);
    return thread;
  });

  private final Map<String, Run> runs = new HashMap<>();
  private final Deque<String> finishedOrder = new ArrayDeque<>();
  private Run currentRun;
  private MultipleTestsRunner currentRunner;
  private RunCollector currentCollector;

  public RunManager(FitNesseContext ctx, int maxCompletedRuns) {
    this.ctx = ctx;
    this.maxCompletedRuns = maxCompletedRuns;
  }

  /** Queues the test pages under {@code page} that match the filters (both may be null). */
  public Run submit(WikiPage page, String suiteFilter, String excludeSuiteFilter) throws ToolException {
    String target = PagePaths.fullPath(page);
    List<WikiPage> pages = new SuiteContentsFinder(page,
        new SuiteFilter(suiteFilter, excludeSuiteFilter), ctx.getRootPage()).getAllPagesToRunForThisSuite();
    if (pages.isEmpty()) {
      throw new ToolException("No test pages to run under '" + target + "' with the given filters");
    }
    // The run may add suite set-up and tear-down pages, so count what it will really execute.
    TestRun testRun = ctx.testRunFactoryRegistry.createRun(pages);
    Run run;
    synchronized (this) {
      run = new Run(newId(), target, testRun.getPages().size());
      runs.put(run.id(), run);
    }
    try {
      worker.execute(() -> execute(run, testRun));
    } catch (RejectedExecutionException e) {
      synchronized (this) {
        runs.remove(run.id());
      }
      throw new ToolException("The run queue is shut down because FitNesse is stopping; restart FitNesse and try again");
    }
    return run;
  }

  public synchronized Optional<Run> find(String id) {
    return Optional.ofNullable(runs.get(id));
  }

  /**
   * Cancels a queued or running run. A queued run is cancelled at once and will never start; a
   * running one has its test system stopped and ends CANCELLED. A cancel that arrives after the
   * pages finished but before the run is finalised still yields CANCELLED.
   *
   * @return false when no such run exists or it has already finished
   */
  public boolean cancel(String id) {
    MultipleTestsRunner toStop = null;
    RunCollector toKill = null;
    synchronized (this) {
      Run run = runs.get(id);
      if (run == null) return false;
      Optional<RunState> before = run.requestCancel();
      if (before.isEmpty()) return false;
      if (before.get() == RunState.QUEUED) {
        recordFinished(run);
      } else if (run == currentRun) {
        toStop = currentRunner;
        toKill = currentCollector;
      }
    }
    // When the runner is not created yet, the worker sees the cancel flag and stops it itself.
    if (toStop != null) toStop.stop();
    // runner.stop() misses a test system that is still starting; the collector covers that case.
    if (toKill != null) toKill.killTestSystem();
    return true;
  }

  /** Stops the running test (if any) and drops queued runs; the worker thread ends. */
  @Override public void close() {
    MultipleTestsRunner toStop;
    RunCollector toKill;
    synchronized (this) {
      for (Run run : runs.values()) run.requestCancel();
      toStop = currentRunner;
      toKill = currentCollector;
    }
    worker.shutdownNow();
    if (toStop != null) toStop.stop();
    if (toKill != null) toKill.killTestSystem();
  }

  private void execute(Run run, TestRun testRun) {
    if (!run.markRunning()) return; // cancelled while queued
    try {
      MultipleTestsRunner runner = new MultipleTestsRunner(testRun, ctx.testSystemFactory);
      RunCollector collector = new RunCollector(run);
      runner.addTestSystemListener(collector);
      synchronized (this) {
        currentRun = run;
        currentRunner = runner;
        currentCollector = collector;
      }
      // Covers a cancel that arrived between markRunning and publishing the runner.
      if (run.isCancelRequested()) runner.stop();
      runner.executeTestPages();
    } catch (Exception | LinkageError e) {
      run.recordFailureCause(RunCollector.describe(e));
    } finally {
      synchronized (this) {
        currentRun = null;
        currentRunner = null;
        currentCollector = null;
        run.finish();
        recordFinished(run);
      }
    }
  }

  /** Caller holds the monitor. Evicts the oldest finished runs beyond the limit. */
  private void recordFinished(Run run) {
    finishedOrder.addLast(run.id());
    while (finishedOrder.size() > maxCompletedRuns) {
      runs.remove(finishedOrder.removeFirst());
    }
  }

  /** Caller holds the monitor. First 8 hex chars of a random UUID, unique among kept runs. */
  private String newId() {
    String id;
    do {
      id = UUID.randomUUID().toString().substring(0, 8);
    } while (runs.containsKey(id));
    return id;
  }
}
