package io.github.tortaje.fixtures;

import fitnesse.wiki.WikiPage;
import java.util.LinkedHashSet;
import java.util.Set;

/** Finds the packages named by Slim {@code import} tables in a page and its setup pages. */
final class ImportScanner {
  private ImportScanner() {}

  /** Collects the first cell of every row following an {@code |import|} header row. */
  static Set<String> packagesFrom(String wikiText) {
    Set<String> packages = new LinkedHashSet<>();
    boolean inImport = false;
    boolean inTable = false;
    for (String raw : wikiText.split("\r?\n", -1)) {
      String line = stripModifier(raw.trim());
      if (!line.startsWith("|")) {
        inTable = false;
        inImport = false;
        continue;
      }
      String cell = firstCell(line);
      if (!inTable) {
        inTable = true;
        inImport = cell.equalsIgnoreCase("import") && cellCount(line) == 1;
      } else if (inImport && !cell.isEmpty()) {
        packages.add(cell);
      }
    }
    return packages;
  }

  /** Scans the page, all its ancestors and its closest inherited SetUp and SuiteSetUp. */
  static Set<String> packagesFor(WikiPage page) {
    Set<String> packages = new LinkedHashSet<>();
    for (WikiPage p = page; p != null; p = p.isRoot() ? null : p.getParent()) {
      packages.addAll(packagesFrom(p.getData().getContent()));
    }
    for (String name : new String[] {"SetUp", "SuiteSetUp"}) {
      WikiPage inherited = page.getPageCrawler().getClosestInheritedPage(name);
      if (inherited != null) packages.addAll(packagesFrom(inherited.getData().getContent()));
    }
    return packages;
  }

  /** Removes the table modifiers {@code !}, {@code -} and {@code -!} before the first pipe. */
  private static String stripModifier(String line) {
    for (String prefix : new String[] {"-!|", "!|", "-|"}) {
      if (line.startsWith(prefix)) return line.substring(prefix.length() - 1);
    }
    return line;
  }

  private static String firstCell(String line) {
    int end = line.indexOf('|', 1);
    String cell = (end < 0 ? line.substring(1) : line.substring(1, end)).trim();
    if (cell.startsWith("!-") && cell.endsWith("-!") && cell.length() >= 4) {
      cell = cell.substring(2, cell.length() - 2).trim();
    }
    return cell;
  }

  private static int cellCount(String line) {
    String body = line.substring(1);
    if (body.endsWith("|")) body = body.substring(0, body.length() - 1);
    return body.split("\\|", -1).length;
  }
}
