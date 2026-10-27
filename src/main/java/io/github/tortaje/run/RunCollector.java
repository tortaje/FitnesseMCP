package io.github.tortaje.run;

import fitnesse.slim.SlimServer;
import fitnesse.testrunner.TestsRunnerListener;
import fitnesse.testsystems.Assertion;
import fitnesse.testsystems.ExceptionResult;
import fitnesse.testsystems.ExecutionResult;
import fitnesse.testsystems.TestPage;
import fitnesse.testsystems.TestResult;
import fitnesse.testsystems.TestSummary;
import fitnesse.testsystems.TestSystem;
import fitnesse.testsystems.TestSystemListener;
import fitnesse.testsystems.slim.results.SlimExceptionResult;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Records test-system events into a {@link Run}. Slim calls it on the thread that executes the
 * pages (the run worker); {@link Run} synchronizes, so readers on other threads are safe.
 *
 * <p>It also implements {@link TestsRunnerListener} because FitNesse reports a test system that
 * cannot even be created (for example an unknown {@code TEST_SYSTEM}) only through
 * {@code unableToStartTestSystem}, without any {@code testSystemStopped} event.
 */
public final class RunCollector implements TestSystemListener, TestsRunnerListener {
  static final int MAX_MESSAGE_LINES = 20;

  private final Run run;
  /** Written by the worker, read by a cancelling request thread. */
  private volatile TestSystem testSystem;

  public RunCollector(Run run) {
    this.run = run;
  }

  /**
   * FitNesse's own stop cannot reach a test system that is still starting (its run only learns
   * of the test system after start-up returns), so a cancel during start-up is enforced here.
   */
  @Override public void testSystemStarted(TestSystem started) {
    testSystem = started;
    if (run.isCancelRequested()) started.kill();
  }

  @Override public void testStarted(TestPage testPage) {
    if (run.isCancelRequested()) {
      killTestSystem(); // the page then fails at its first Slim call and never completes
      return;
    }
    run.pageStarted(pagePath(testPage));
  }

  /**
   * Kills the started test system, if any. Called by the run manager after setting the cancel
   * flag. {@link #testSystemStarted} publishes the test system (volatile) before reading the flag,
   * and the canceller sets the flag (under the run's lock) before reading the test system, so at
   * least one of them sees the other and the Slim process is always killed.
   */
  void killTestSystem() {
    TestSystem started = testSystem;
    if (started != null) started.kill();
  }

  @Override public void testOutputChunk(TestPage testPage, String output) {
    run.appendOutput(output);
  }

  /** Deprecated overload; some test systems may still call it. Appends to the open page. */
  @Override @Deprecated public void testOutputChunk(String output) {
    run.appendOutput(output);
  }

  @Override public void testAssertionVerified(Assertion assertion, TestResult result) {
    if (result == null) return;
    ExecutionResult outcome = result.getExecutionResult();
    if (outcome != ExecutionResult.FAIL && outcome != ExecutionResult.ERROR) return;
    run.addFailure(new Failure(Failure.ASSERTION, instructionId(assertion),
        result.hasExpected() ? result.getExpected() : null,
        result.hasActual() ? result.getActual() : null,
        result.hasMessage() ? result.getMessage() : null));
  }

  @Override public void testExceptionOccurred(Assertion assertion, ExceptionResult result) {
    run.addFailure(new Failure(Failure.EXCEPTION, instructionId(assertion), null, null,
        firstLines(exceptionText(result))));
  }

  @Override public void testComplete(TestPage testPage, TestSummary summary) {
    run.pageCompleted(Counts.of(summary));
  }

  @Override public void testSystemStopped(TestSystem testSystem, Throwable cause) {
    if (cause != null) run.recordFailureCause(describe(cause));
  }

  @Override public void announceNumberTestsToRun(int testsToRun) {
    // Run.totalPages() is known when the run is submitted.
  }

  @Override public void unableToStartTestSystem(String testSystemName, Throwable cause) {
    run.recordFailureCause("Unable to start test system '" + testSystemName + "': " + describe(cause));
  }

  /** "Type: message" for the throwable and each of its causes, at most 20 lines. */
  static String describe(Throwable cause) {
    if (cause == null) return "unknown cause";
    StringBuilder text = new StringBuilder(cause.toString());
    Throwable c = cause.getCause();
    for (int depth = 1; c != null && depth < MAX_MESSAGE_LINES; depth++, c = c.getCause()) {
      text.append("\nCaused by: ").append(c);
    }
    return firstLines(text.toString());
  }

  static String firstLines(String text) {
    if (text == null) return null;
    String[] lines = text.split("\r?\n", -1);
    if (lines.length <= MAX_MESSAGE_LINES) return text;
    return Arrays.stream(lines).limit(MAX_MESSAGE_LINES).collect(Collectors.joining("\n"));
  }

  /**
   * Slim gives a short message only for {@code message:<<...>>} exceptions; for ordinary ones the
   * text (exception and stack trace) is only in {@link SlimExceptionResult#getException()}.
   */
  private static String exceptionText(ExceptionResult result) {
    if (result == null) return null;
    String message = result.getMessage();
    if (message == null && result instanceof SlimExceptionResult slim && slim.getException() != null) {
      message = slim.getException();
      if (message.startsWith(SlimServer.EXCEPTION_TAG)) message = message.substring(SlimServer.EXCEPTION_TAG.length());
    }
    return message;
  }

  private static String pagePath(TestPage page) {
    String path = page.getFullPath();
    return path.startsWith(".") ? path.substring(1) : path;
  }

  private static String instructionId(Assertion assertion) {
    return assertion == null || assertion.getInstruction() == null ? null : assertion.getInstruction().getId();
  }
}
