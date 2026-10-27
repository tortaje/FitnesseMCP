package io.github.tortaje.run;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts error messages from a page's HTML test output. Slim reports some problems only there:
 * a malformed table ("Bad table!") is counted as an exception without any assertion or exception
 * event, so the collector never sees its message.
 */
final class TableErrors {
  static final int MAX_MESSAGE_CHARS = 300;
  static final int MAX_MESSAGES = 20;

  /** An element whose class list contains {@code error}, e.g. {@code <span class="error">}. */
  private static final Pattern ERROR_OPEN = Pattern.compile(
      "<(span|td|div)\\b[^>]*\\bclass\\s*=\\s*\"(?:[^\"]*\\s)?error(?:\\s[^\"]*)?\"[^>]*>",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern TAG = Pattern.compile("<[^>]*>");
  private static final Pattern SPACES = Pattern.compile("[\\s\\u00a0]+");

  private TableErrors() {}

  /** The text of each error element, in document order, without tags and trimmed. */
  static List<String> extract(String html) {
    List<String> out = new ArrayList<>();
    if (html == null) return out;
    Matcher open = ERROR_OPEN.matcher(html);
    int from = 0;
    while (out.size() < MAX_MESSAGES && open.find(from)) {
      int end = closingTag(html, open.group(1), open.end());
      String text = plainText(html.substring(open.end(), end));
      if (!text.isEmpty()) out.add(text.length() > MAX_MESSAGE_CHARS ? text.substring(0, MAX_MESSAGE_CHARS) : text);
      from = Math.max(end, open.end());
    }
    return out;
  }

  /** Index of the closing tag matching an element opened just before {@code start}. */
  private static int closingTag(String html, String tag, int start) {
    Pattern sameTag = Pattern.compile("<(/?)" + tag + "\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    Matcher m = sameTag.matcher(html);
    int depth = 1;
    int from = start;
    while (m.find(from)) {
      depth += m.group(1).isEmpty() ? 1 : -1;
      if (depth == 0) return m.start();
      from = m.end();
    }
    return html.length();
  }

  private static String plainText(String html) {
    String text = TAG.matcher(html).replaceAll(" ");
    text = text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&#x27;", "'").replace("&apos;", "'").replace("&nbsp;", " ")
        .replace("&amp;", "&");
    return SPACES.matcher(text).replaceAll(" ").trim();
  }
}
