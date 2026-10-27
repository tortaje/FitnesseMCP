package io.github.tortaje.testsupport;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Captures the records of one {@code java.util.logging} logger and keeps them off the console
 * while open, so expected warnings do not clutter the test output.
 */
public final class LogCapture extends Handler implements AutoCloseable {
  private final Logger logger;
  private final boolean useParentHandlers;
  private final List<LogRecord> records = new ArrayList<>();

  private LogCapture(Logger logger) {
    this.logger = logger;
    this.useParentHandlers = logger.getUseParentHandlers();
    logger.addHandler(this);
    logger.setUseParentHandlers(false);
  }

  public static LogCapture of(Class<?> loggerClass) {
    return new LogCapture(Logger.getLogger(loggerClass.getName()));
  }

  public synchronized List<LogRecord> records() { return List.copyOf(records); }

  @Override public synchronized void publish(LogRecord record) { records.add(record); }

  @Override public void flush() {}

  @Override public void close() {
    logger.removeHandler(this);
    logger.setUseParentHandlers(useParentHandlers);
  }
}
