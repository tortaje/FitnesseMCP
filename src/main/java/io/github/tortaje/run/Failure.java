package io.github.tortaje.run;

/**
 * One failed assertion, exception or table-level error on a page.
 *
 * @param kind "assertion", "exception" or "table" (an error Slim reports only in the page output,
 *     such as a malformed "Bad table!"; it has no instructionId)
 * @param expected null when unknown, always null for exceptions
 * @param actual null when unknown, always null for exceptions
 * @param message null when the test system gave none
 */
public record Failure(String kind, String instructionId, String expected, String actual, String message) {
  public static final String ASSERTION = "assertion";
  public static final String EXCEPTION = "exception";
  public static final String TABLE = "table";

  static final String UNEXPLAINED_TABLE_ERROR = "Table-level error; see output";

  /** Stands for errors that were counted but whose text could not be found in the output. */
  public static Failure unexplainedTableError() {
    return new Failure(TABLE, null, null, null, UNEXPLAINED_TABLE_ERROR);
  }

  boolean isUnexplainedTableError() {
    return TABLE.equals(kind) && UNEXPLAINED_TABLE_ERROR.equals(message);
  }
}
