package io.github.tortaje.wiki;

import fitnesse.wiki.PageData;
import fitnesse.wiki.PageType;
import fitnesse.wiki.WikiPage;
import fitnesse.wiki.WikiPageProperty;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.json.JSONObject;

/** JSON views of wiki pages shared by the wiki tools. */
final class PageJson {
  private PageJson() {}

  /** "Test", "Suite" or "Static". */
  static String type(WikiPage p) {
    return PageType.fromWikiPage(p).toString();
  }

  /** Tags are the comma-separated "Suites" page attribute. */
  static List<String> tags(WikiPage p) {
    List<String> tags = new ArrayList<>();
    String raw = p.getData().getAttribute(WikiPageProperty.SUITES);
    if (raw == null) return tags;
    for (String t : raw.split(",")) {
      String trimmed = t.trim();
      if (!trimmed.isEmpty()) tags.add(trimmed);
    }
    return tags;
  }

  /**
   * ISO-8601 instant of the last modification, or null when the page has no recorded (or no
   * parsable) modification time. FitNesse itself would answer "now" for a missing value.
   */
  static String lastModified(WikiPage p) {
    WikiPageProperty props = p.getData().getProperties();
    if (!props.has(PageData.PropertyLAST_MODIFIED)) return null;
    try {
      Date d = props.getLastModificationTime();
      return d == null ? null : d.toInstant().toString();
    } catch (RuntimeException e) {
      return null;
    }
  }

  static JSONObject summary(WikiPage p) {
    return new JSONObject()
        .put("path", PagePaths.fullPath(p))
        .put("type", type(p))
        .put("tags", tags(p));
  }
}
