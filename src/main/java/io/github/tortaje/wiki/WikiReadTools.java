package io.github.tortaje.wiki;

import fitnesse.FitNesseContext;
import fitnesse.wiki.WikiPage;
import io.github.tortaje.protocol.Tool;
import io.github.tortaje.protocol.ToolException;
import io.github.tortaje.protocol.ToolRegistry;
import io.github.tortaje.protocol.ToolResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only wiki tools: get_page, list_pages and search_pages. */
public final class WikiReadTools {
  static final int MAX_DEPTH = 10;
  static final int MAX_RESULTS = 100;
  static final int MAX_EXCERPT = 200;

  private static final String PATH_HELP = "Page path as dot-separated WikiWords, e.g. "
      + "FrontPage.MySuite.MyTest. Empty or 'root' means the wiki root.";

  private WikiReadTools() {}

  public static void registerAll(ToolRegistry r, FitNesseContext ctx) {
    r.register(new GetPage(ctx));
    r.register(new ListPages(ctx));
    r.register(new SearchPages(ctx));
  }

  private static JSONObject pathProp(String description) {
    return new JSONObject().put("type", "string").put("description", description);
  }

  private static List<WikiPage> sortedChildren(WikiPage page) {
    List<WikiPage> children = new ArrayList<>();
    for (WikiPage c : page.getChildren()) {
      if (!c.isSymbolicPage()) children.add(c); // symbolic links can form cycles
    }
    children.sort(Comparator.comparing(WikiPage::getName));
    return children;
  }

  private static final class GetPage implements Tool {
    private final FitNesseContext ctx;

    GetPage(FitNesseContext ctx) { this.ctx = ctx; }

    public String name() { return "get_page"; }

    public String description() {
      return "Returns one wiki page as JSON: path, raw wiki-text content, type (Test, Suite or "
          + "Static), tags, lastModified (ISO-8601, or null when the page has no recorded modification time) and the names of its child pages. "
          + PATH_HELP;
    }

    public JSONObject inputSchema() {
      return new JSONObject().put("type", "object")
          .put("properties", new JSONObject().put("path", pathProp(PATH_HELP)))
          .put("required", new JSONArray().put("path"));
    }

    public ToolResult call(JSONObject args) throws ToolException {
      WikiPage page = PagePaths.resolve(ctx.getRootPage(), args.getString("path"));
      JSONArray children = new JSONArray();
      for (WikiPage c : sortedChildren(page)) children.put(c.getName());
      String lastModified = PageJson.lastModified(page);
      JSONObject out = PageJson.summary(page)
          .put("content", page.getData().getContent())
          .put("lastModified", lastModified == null ? JSONObject.NULL : lastModified)
          .put("children", children);
      return ToolResult.json(out);
    }
  }

  private static final class ListPages implements Tool {
    private final FitNesseContext ctx;

    ListPages(FitNesseContext ctx) { this.ctx = ctx; }

    public String name() { return "list_pages"; }

    public String description() {
      return "Lists descendant pages of a page as {pages:[{path,type,tags}]}, depth-first. "
          + "'depth' is how many levels below 'path' to include (default 1, maximum 10). "
          + PATH_HELP;
    }

    public JSONObject inputSchema() {
      return new JSONObject().put("type", "object").put("properties", new JSONObject()
          .put("path", pathProp(PATH_HELP + " Defaults to the root."))
          .put("depth", new JSONObject().put("type", "integer")
              .put("description", "Levels to descend, 1 to 10 (default 1).")));
    }

    public ToolResult call(JSONObject args) throws ToolException {
      int depth = args.has("depth") && !args.isNull("depth") ? args.getInt("depth") : 1;
      if (depth < 1 || depth > MAX_DEPTH) {
        throw new ToolException("Invalid depth " + depth + ": use a value from 1 to " + MAX_DEPTH);
      }
      WikiPage start = PagePaths.resolve(ctx.getRootPage(), args.optString("path", ""));
      JSONArray pages = new JSONArray();
      collect(start, depth, pages);
      return ToolResult.json(new JSONObject().put("pages", pages));
    }

    private void collect(WikiPage page, int depth, JSONArray out) {
      if (depth == 0) return;
      for (WikiPage child : sortedChildren(page)) {
        out.put(PageJson.summary(child));
        collect(child, depth - 1, out);
      }
    }
  }

  private static final class SearchPages implements Tool {
    private final FitNesseContext ctx;

    SearchPages(FitNesseContext ctx) { this.ctx = ctx; }

    public String name() { return "search_pages"; }

    public String description() {
      return "Case-insensitive substring search over page names and/or page content below 'path'; "
          + "'in' is 'title', 'content' or 'both' (default). Symbolic-link pages are skipped. "
          + "Returns {results:[{path,matchedIn:'title'|'content',excerpt?}],truncated} with at "
          + "most " + MAX_RESULTS + " results; excerpts (content matches) are at most "
          + MAX_EXCERPT + " characters around the first hit. " + PATH_HELP;
    }

    public JSONObject inputSchema() {
      return new JSONObject().put("type", "object").put("properties", new JSONObject()
          .put("query", new JSONObject().put("type", "string")
              .put("description", "Text to look for."))
          .put("in", new JSONObject().put("type", "string")
              .put("description", "Where to look: 'title', 'content' or 'both' (default)."))
          .put("path", pathProp("Subtree to search. " + PATH_HELP + " Defaults to the root.")))
          .put("required", new JSONArray().put("query"));
    }

    public ToolResult call(JSONObject args) throws ToolException {
      String query = args.getString("query");
      if (query.isEmpty()) {
        throw new ToolException("Argument 'query' must not be empty: give the text to search for");
      }
      String in = args.optString("in", "both");
      if (!in.equals("title") && !in.equals("content") && !in.equals("both")) {
        throw new ToolException("Invalid value '" + in
            + "' for argument 'in': use 'title', 'content' or 'both'");
      }
      WikiPage start = PagePaths.resolve(ctx.getRootPage(), args.optString("path", ""));
      JSONArray results = new JSONArray();
      boolean truncated = search(start, query.toLowerCase(Locale.ROOT), query.length(), in, results);
      return ToolResult.json(new JSONObject().put("results", results).put("truncated", truncated));
    }

    /** Returns true when the result cap stopped the search. */
    private boolean search(WikiPage page, String q, int qLen, String in, JSONArray out) {
      for (WikiPage child : sortedChildren(page)) {
        String content = child.getData().getContent();
        boolean titleHit = !in.equals("content")
            && child.getName().toLowerCase(Locale.ROOT).contains(q);
        int at = in.equals("title") ? -1 : content.toLowerCase(Locale.ROOT).indexOf(q);
        if (titleHit || at >= 0) {
          if (out.length() >= MAX_RESULTS) return true;
          JSONObject hit = new JSONObject().put("path", PagePaths.fullPath(child));
          if (titleHit) {
            hit.put("matchedIn", "title");
          } else {
            hit.put("matchedIn", "content").put("excerpt", excerpt(content, at, qLen));
          }
          out.put(hit);
        }
        if (search(child, q, qLen, in, out)) return true;
      }
      return false;
    }

    private static String excerpt(String content, int at, int qLen) {
      int spare = Math.max(0, MAX_EXCERPT - qLen);
      int from = Math.max(0, at - spare / 2);
      int to = Math.min(content.length(), from + MAX_EXCERPT);
      from = Math.max(0, to - MAX_EXCERPT);
      return content.substring(from, to);
    }
  }
}
