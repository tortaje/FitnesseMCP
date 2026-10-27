package io.github.tortaje.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RunTest {
  @Test void snapshotOfQueuedRun() {
    Run.Status status = new Run("abcd1234", "SuitePage", 3).snapshot();

    assertThat(status).isEqualTo(new Run.Status("abcd1234", RunState.QUEUED, "SuitePage", 3, 0,
        Counts.ZERO, Duration.ZERO, Optional.empty()));
  }

  @Test void snapshotReflectsProgressAndFinalState() {
    Run run = new Run("abcd1234", "SuitePage", 2);
    run.markRunning();
    run.pageStarted("SuitePage.OneTest");
    run.pageCompleted(new Counts(2, 1, 0, 0));
    run.recordFailureCause("boom");
    run.finish();

    Run.Status status = run.snapshot();
    assertThat(status.state()).isEqualTo(RunState.FAILED);
    assertThat(status.pagesDone()).isEqualTo(1);
    assertThat(status.totals()).isEqualTo(new Counts(2, 1, 0, 0));
    assertThat(status.failureCause()).contains("boom");
    assertThat(status.elapsed()).isEqualTo(run.elapsed());
  }

  private static PageResult completePage(String output, Counts counts, Failure... recorded) {
    Run run = new Run("abcd1234", "OneTest", 1);
    run.markRunning();
    run.pageStarted("OneTest");
    run.appendOutput(output);
    for (Failure f : recorded) run.addFailure(f);
    run.pageCompleted(counts);
    return run.pageResults().get(0);
  }

  @Test void unexplainedErrorsAreTakenFromTheOutput() {
    PageResult page = completePage(TableErrorsTest.BAD_SCRIPT, new Counts(0, 0, 0, 1));

    assertThat(page.failures()).containsExactly(new Failure(Failure.TABLE, null, null, null,
        "script: Bad table! Too few columns in row 2. Expected a function in column 2."));
  }

  @Test void unexplainedErrorsWithoutTextGetAFallbackFailure() {
    PageResult page = completePage("<table></table>", new Counts(0, 1, 0, 0));

    assertThat(page.failures()).containsExactly(Failure.unexplainedTableError());
    assertThat(Failure.unexplainedTableError().message()).isEqualTo("Table-level error; see output");
  }

  @Test void explainedCountsAddNoTableFailure() {
    Failure exception = new Failure(Failure.EXCEPTION, "id_1", null, null, "kaboom");
    PageResult page = completePage("<span class=\"error\">kaboom</span>", new Counts(0, 0, 0, 1), exception);

    assertThat(page.failures()).containsExactly(exception);
  }

  @Test void errorTextsAlreadyRecordedAreNotDuplicated() {
    Failure exception = new Failure(Failure.EXCEPTION, "id_1", null, null, "Could not invoke constructor for Nope[0]");
    PageResult page = completePage("<span class=\"error\">Could not invoke constructor for Nope[0]</span>"
        + TableErrorsTest.BAD_SCRIPT, new Counts(0, 0, 0, 2), exception);

    assertThat(page.failures()).extracting(Failure::kind).containsExactly(Failure.EXCEPTION, Failure.TABLE);
  }
}
