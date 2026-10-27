package io.github.tortaje.run;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One asynchronous test run. The run worker writes to it (through {@link RunCollector} and
 * {@link RunManager}) while HTTP request threads read it, so every method synchronizes on this
 * instance and accessors return immutable values or snapshots.
 */
public final class Run {
  static final int MAX_OUTPUT_CHARS = 65536;

  private final String id;
  private final String target;
  private final int totalPages;

  private RunState state = RunState.QUEUED;
  private boolean cancelRequested;
  private long startNanos;
  private long endNanos;
  private int pagesDone;
  private Counts totals = Counts.ZERO;
  private final List<PageResult> pageResults = new ArrayList<>();
  private OpenPage openPage;
  private String failureCause;

  Run(String id, String target, int totalPages) {
    this.id = id;
    this.target = target;
    this.totalPages = totalPages;
  }

  public String id() { return id; }

  public String target() { return target; }

  public int totalPages() { return totalPages; }

  public synchronized RunState state() { return state; }

  public synchronized int pagesDone() { return pagesDone; }

  public synchronized Counts totals() { return totals; }

  /** Zero while queued (and for a run cancelled before it started); running time otherwise. */
  public synchronized Duration elapsed() {
    if (startNanos == 0) return Duration.ZERO;
    long end = state.isFinished() ? endNanos : System.nanoTime();
    return Duration.ofNanos(end - startNanos);
  }

  /** Snapshot of the finished pages, in execution order. */
  public synchronized List<PageResult> pageResults() { return List.copyOf(pageResults); }

  public synchronized Optional<String> failureCause() { return Optional.ofNullable(failureCause); }

  /** Everything a status report needs, read under one lock so the values are consistent. */
  public synchronized Status snapshot() {
    return new Status(id, state, target, totalPages, pagesDone, totals, elapsed(), failureCause());
  }

  /** A consistent point-in-time view of a run (see {@link #snapshot()}). */
  public record Status(String id, RunState state, String target, int totalPages, int pagesDone,
      Counts totals, Duration elapsed, Optional<String> failureCause) {}

  // ---- written by the worker thread -------------------------------------------------------

  /** Moves a queued run to RUNNING; false when it was cancelled meanwhile and must be skipped. */
  synchronized boolean markRunning() {
    if (state != RunState.QUEUED) return false;
    state = RunState.RUNNING;
    startNanos = System.nanoTime();
    return true;
  }

  synchronized boolean isCancelRequested() { return cancelRequested; }

  synchronized void pageStarted(String path) {
    closeOpenPage(Counts.ZERO, false);
    openPage = new OpenPage(path);
  }

  synchronized void addFailure(Failure failure) {
    if (openPage != null) openPage.failures.add(failure);
  }

  synchronized void appendOutput(String chunk) {
    if (openPage == null || chunk == null) return;
    StringBuilder out = openPage.output;
    int room = MAX_OUTPUT_CHARS - out.length();
    if (room > 0) out.append(chunk, 0, Math.min(room, chunk.length()));
  }

  synchronized void pageCompleted(Counts counts) {
    if (openPage != null) explainTableErrors(openPage, counts);
    closeOpenPage(counts, true);
  }

  /**
   * Slim counts some errors (malformed tables) without an assertion or exception event. When the
   * counts exceed the recorded failures, the missing messages are taken from the page output.
   */
  private static void explainTableErrors(OpenPage page, Counts counts) {
    if (counts.wrong() + counts.exceptions() <= page.failures.size()) return;
    List<String> known = page.failures.stream().map(Failure::message)
        .filter(m -> m != null && !m.isBlank()).toList();
    boolean added = false;
    for (String text : TableErrors.extract(page.output.toString())) {
      if (known.stream().anyMatch(k -> k.contains(text) || text.contains(k))) continue;
      page.failures.add(new Failure(Failure.TABLE, null, null, null, text));
      added = true;
    }
    if (!added) page.failures.add(Failure.unexplainedTableError());
  }

  /** Keeps the first cause only: it is the root problem, later ones are consequences. */
  synchronized void recordFailureCause(String cause) {
    if (failureCause == null) failureCause = cause;
  }

  /** Final state: CANCELLED wins over everything, then FAILED when a cause was recorded. */
  synchronized void finish() {
    if (state.isFinished()) return;
    // A page interrupted by a crash or a cancel never got testComplete; keep what it produced.
    closeOpenPage(Counts.ZERO, false);
    if (cancelRequested) state = RunState.CANCELLED;
    else if (failureCause != null) state = RunState.FAILED;
    else state = RunState.COMPLETED;
    endNanos = System.nanoTime();
  }

  // ---- written by request threads ---------------------------------------------------------

  /**
   * Requests cancellation. A queued run becomes CANCELLED at once; a running one is flagged and
   * becomes CANCELLED when the worker finishes it.
   *
   * @return the state before the request, or empty when the run had already finished
   */
  synchronized Optional<RunState> requestCancel() {
    RunState before = state;
    if (before.isFinished() || cancelRequested) return Optional.empty();
    cancelRequested = true;
    if (before == RunState.QUEUED) {
      state = RunState.CANCELLED;
      endNanos = System.nanoTime();
    }
    return Optional.of(before);
  }

  private void closeOpenPage(Counts counts, boolean completed) {
    if (openPage == null) return;
    pageResults.add(new PageResult(openPage.path, counts, openPage.failures, openPage.output.toString()));
    if (completed) {
      pagesDone++;
      totals = totals.plus(counts);
    }
    openPage = null;
  }

  private static final class OpenPage {
    final String path;
    final List<Failure> failures = new ArrayList<>();
    final StringBuilder output = new StringBuilder();

    OpenPage(String path) { this.path = path; }
  }
}
