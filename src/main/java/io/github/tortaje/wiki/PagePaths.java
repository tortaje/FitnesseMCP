package io.github.tortaje.wiki;

import fitnesse.wiki.PathParser;
import fitnesse.wiki.WikiPage;
import fitnesse.wiki.WikiPagePath;
import io.github.tortaje.protocol.ToolException;

/** Turns LLM-written page paths into wiki pages, with clear errors for odd forms. */
public final class PagePaths {
  private static final String ROOT = "root";

  private PagePaths() {}

  /** Parses a dotted path; blank or "root" gives the empty (root) path. */
  static WikiPagePath parse(String path) throws ToolException {
    String p = path == null ? "" : path.trim();
    if (p.isEmpty() || p.equals(ROOT)) return new WikiPagePath();
    String stripped = p.startsWith(".") ? p.substring(1) : p;
    WikiPagePath parsed = stripped.isEmpty() || stripped.endsWith(".") ? null : PathParser.parse(stripped);
    if (parsed == null) {
      throw new ToolException("Invalid page path '" + p
          + "': use dot-separated WikiWords like FrontPage.MySuite");
    }
    return parsed;
  }

  public static WikiPage resolve(WikiPage root, String path) throws ToolException {
    WikiPagePath parsed = parse(path);
    if (parsed.isEmpty()) return root;
    WikiPage page = root.getPageCrawler().getPage(parsed);
    if (page != null) return page;
    WikiPagePath ancestor = parsed;
    WikiPage found = null;
    while (found == null && !ancestor.isEmpty()) {
      ancestor = ancestor.parentPath();
      found = root.getPageCrawler().getPage(ancestor);
    }
    String closest = ancestor.isEmpty() || found == null ? ROOT : join(ancestor);
    throw new ToolException("Page '" + path.trim().replaceFirst("^\\.", "")
        + "' does not exist; closest existing ancestor: '" + closest + "'");
  }

  /** Dotted full path of a page, or "root" for the root page. */
  public static String fullPath(WikiPage page) {
    String full = join(page.getPageCrawler().getFullPath());
    return full.isEmpty() ? ROOT : full;
  }

  private static String join(WikiPagePath path) {
    return String.join(".", path.getNames());
  }
}
