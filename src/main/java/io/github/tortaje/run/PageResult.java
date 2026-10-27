package io.github.tortaje.run;

import java.util.List;

/** Result of one executed page; {@code output} is the page's HTML output, capped at 65536 chars. */
public record PageResult(String path, Counts counts, List<Failure> failures, String output) {
  public PageResult {
    failures = List.copyOf(failures);
  }
}
