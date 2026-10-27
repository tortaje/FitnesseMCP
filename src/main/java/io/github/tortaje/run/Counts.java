package io.github.tortaje.run;

import fitnesse.testsystems.TestSummary;

/** Assertion counts of a page or of a whole run. */
public record Counts(int right, int wrong, int ignores, int exceptions) {
  public static final Counts ZERO = new Counts(0, 0, 0, 0);

  public static Counts of(TestSummary s) {
    return new Counts(s.getRight(), s.getWrong(), s.getIgnores(), s.getExceptions());
  }

  public Counts plus(Counts o) {
    return new Counts(right + o.right, wrong + o.wrong, ignores + o.ignores, exceptions + o.exceptions);
  }
}
