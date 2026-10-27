package io.github.tortaje.wiki;

import fitnesse.FitNesseContext;
import fitnesse.http.MockRequest;
import fitnesse.http.Response;
import fitnesse.http.SimpleResponse;
import fitnesse.responders.refactoring.MovePageResponder;
import fitnesse.responders.refactoring.RenamePageResponder;
import fitnesse.wiki.PageData;
import fitnesse.wiki.PathParser;
import fitnesse.wiki.WikiPage;
import fitnesse.wiki.WikiPagePath;
import fitnesse.wiki.WikiPageProperty;
import fitnesse.wiki.WikiPageUtil;
import io.github.tortaje.protocol.Tool;
import io.github.tortaje.protocol.ToolException;
import io.github.tortaje.protocol.ToolRegistry;
import io.github.tortaje.protocol.ToolResult;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Wiki write tools: save_page, move_page and delete_page. */
public final class WikiWriteTools {
  private static final String PATH_HELP = "Page path as dot-separated WikiWords, e.g. "
      + "FrontPage.MySuite.MyTest.";

  private static final String FILES_DIR = "files";

  private WikiWriteTools() {}

  public static void registerAll(ToolRegistry r, FitNesseContext ctx) {
    r.register(new SavePage(ctx));
    r.register(new MovePage(ctx));
    r.register(new DeletePage(ctx));
  }

  private static JSONObject prop(String type, String description) {
    return new JSONObject().put("type", type).put("description", description);
  }

  private static WikiPage find(WikiPage root, WikiPagePath path) {
    return path.isEmpty() ? root : root.getPageCrawler().getPage(path);
  }

  private static String last(WikiPagePath path) {
    List<String> names = path.getNames();
    return names.get(names.size() - 1);
  }

  /** The page found after a save; a page that cannot be read back is a tool error, not an NPE. */
  static WikiPage requireSaved(WikiPage saved, WikiPagePath path) throws ToolException {
    if (saved == null) {
      throw new ToolException("Page '" + dotted(path) + "' was written but cannot be read back; "
          + "check it with get_page or list_pages before retrying");
    }
    return saved;
  }

  /** FitNesse serves the top-level 'files' name as the attachments directory, never as a page. */
  private static void requireNotReserved(WikiPagePath path) throws ToolException {
    String first = path.getNames().get(0);
    if (first.equalsIgnoreCase(FILES_DIR)) {
      throw new ToolException("The top-level name '" + first + "' is reserved by "
          + "FitNesse for attachments (the files directory); use upload_file to store files, or "
          + "choose another page name");
    }
  }

  private static String dotted(WikiPagePath path) {
    return String.join(".", path.getNames());
  }

  private static final class SavePage implements Tool {
    private final FitNesseContext ctx;

    SavePage(FitNesseContext ctx) { this.ctx = ctx; }

    public String name() { return "save_page"; }

    public String description() {
      return "Creates or updates a wiki page with raw wiki-text content. 'type' (Test, Suite or "
          + "Static) and 'tags' are optional: when omitted, an existing page keeps its type and tags. "
          + "Missing parent pages are an error unless 'createParents' is true. 'expectedLastModified' "
          + "(the lastModified from get_page) makes the save fail if the page changed meanwhile. "
          + "The top-level name 'files' is reserved for attachments (use upload_file). "
          + "Returns {path, lastModified}. " + PATH_HELP;
    }

    public JSONObject inputSchema() {
      return new JSONObject().put("type", "object")
          .put("properties", new JSONObject()
              .put("path", prop("string", PATH_HELP))
              .put("content", prop("string", "Raw wiki-text content of the page."))
              .put("type", prop("string", "Page type: Test, Suite or Static."))
              .put("tags", new JSONObject().put("type", "array")
                  .put("items", new JSONObject().put("type", "string"))
                  .put("description", "Page tags; replaces the existing tags."))
              .put("createParents", prop("boolean",
                  "Create missing parent pages (default false)."))
              .put("expectedLastModified", prop("string",
                  "ISO-8601 lastModified from get_page; the save is refused if the page differs.")))
          .put("required", new JSONArray().put("path").put("content"));
    }

    public ToolResult call(JSONObject args) throws ToolException {
      WikiPage root = ctx.getRootPage();
      WikiPagePath path = PagePaths.parse(args.getString("path"));
      if (path.isEmpty()) {
        throw new ToolException("Cannot save the wiki root page; give a path like FrontPage.MyPage");
      }
      String type = args.has("type") && !args.isNull("type") ? args.getString("type") : null;
      if (type != null && !type.equals("Test") && !type.equals("Suite") && !type.equals("Static")) {
        throw new ToolException("Invalid type '" + type + "': use Test, Suite or Static");
      }
      requireNotReserved(path);
      String content = args.getString("content").replace("\r\n", "\n");

      WikiPage page = find(root, path);
      if (page == null) {
        if (args.has("expectedLastModified") && !args.isNull("expectedLastModified")) {
          throw new ToolException("Page '" + dotted(path) + "' does not exist, so "
              + "expectedLastModified cannot match; omit it to create the page");
        }
        if (!args.optBoolean("createParents", false) && find(root, path.parentPath()) == null) {
          throw new ToolException("Parent page '" + dotted(path.parentPath()) + "' does not exist; "
              + "create it first or set createParents to true");
        }
        page = WikiPageUtil.addPage(root, path, content);
      } else {
        checkExpected(page, args);
      }

      PageData data = page.getData();
      data.setContent(content);
      if (type != null) applyType(data, type);
      if (args.has("tags") && !args.isNull("tags")) {
        List<String> tags = new ArrayList<>();
        JSONArray arr = args.getJSONArray("tags");
        for (int i = 0; i < arr.length(); i++) tags.add(arr.getString(i));
        data.setOrRemoveAttribute(WikiPageProperty.SUITES, String.join(",", tags));
      }
      page.commit(data);

      WikiPage saved = requireSaved(find(root, path), path);
      String lastModified = PageJson.lastModified(saved);
      return ToolResult.json(new JSONObject()
          .put("path", PagePaths.fullPath(saved))
          .put("lastModified", lastModified == null ? JSONObject.NULL : lastModified));
    }

    private static void checkExpected(WikiPage page, JSONObject args) throws ToolException {
      if (!args.has("expectedLastModified") || args.isNull("expectedLastModified")) return;
      String expectedText = args.getString("expectedLastModified");
      Instant expected;
      try {
        expected = Instant.parse(expectedText);
      } catch (DateTimeParseException e) {
        throw new ToolException("Invalid expectedLastModified '" + expectedText
            + "': use an ISO-8601 instant such as 2026-01-31T12:00:00Z");
      }
      String current = PageJson.lastModified(page);
      boolean same = current != null
          && Instant.parse(current).getEpochSecond() == expected.getEpochSecond();
      if (!same) {
        throw new ToolException("Page was modified since " + expectedText + "; current lastModified is "
            + current + ". Re-read it with get_page and merge your changes.");
      }
    }

    /** Same effect as SavePropertiesResponder: exactly one type attribute, none for Static. */
    private static void applyType(PageData data, String type) {
      for (String t : PageData.PAGE_TYPE_ATTRIBUTES) data.removeAttribute(t);
      if (!type.equals("Static")) data.setAttribute(type);
    }
  }

  private static final class MovePage implements Tool {
    private final FitNesseContext ctx;

    MovePage(FitNesseContext ctx) { this.ctx = ctx; }

    public String name() { return "move_page"; }

    public String description() {
      return "Moves a page (with its children) to a new parent and/or renames it, rewriting "
          + "references to it in other pages. Give 'newParent' (a page path, or 'root'), 'newName' "
          + "(a single WikiWord), or both. A page cannot end up at the top-level name 'files', which is "
          + "reserved for attachments. Returns {path} with the new full path. " + PATH_HELP;
    }

    public JSONObject inputSchema() {
      return new JSONObject().put("type", "object")
          .put("properties", new JSONObject()
              .put("path", prop("string", PATH_HELP))
              .put("newParent", prop("string", "Path of the new parent page, or 'root'."))
              .put("newName", prop("string", "New single-WikiWord name of the page.")))
          .put("required", new JSONArray().put("path"));
    }

    public ToolResult call(JSONObject args) throws ToolException {
      WikiPage root = ctx.getRootPage();
      boolean hasParent = args.has("newParent") && !args.isNull("newParent");
      boolean hasName = args.has("newName") && !args.isNull("newName");
      if (!hasParent && !hasName) {
        throw new ToolException("Nothing to do: give newParent, newName, or both");
      }
      WikiPage page = PagePaths.resolve(root, args.getString("path"));
      if (page.isRoot()) throw new ToolException("Cannot move the wiki root page");
      WikiPagePath current = page.getPageCrawler().getFullPath();

      WikiPagePath parentPath = current.parentPath();
      if (hasParent) {
        parentPath = PagePaths.parse(args.getString("newParent"));
        if (find(root, parentPath) == null) {
          throw new ToolException("New parent '" + args.getString("newParent")
              + "' does not exist; create it first or choose an existing page");
        }
      }
      String finalName = last(current);
      if (hasName) {
        finalName = args.getString("newName");
        if (!PathParser.isSingleWikiWord(finalName) || !Character.isUpperCase(finalName.charAt(0))) {
          throw new ToolException("Invalid newName '" + finalName
              + "': use a single capitalised name without dots, such as MyPage");
        }
      }
      WikiPage newParent = find(root, parentPath);
      WikiPagePath target = parentPath.copy();
      target.addNameToEnd(finalName);
      requireNotReserved(target);
      if (target.equals(current)) {
        throw new ToolException("Page '" + dotted(current) + "' is already at that location");
      }
      if (newParent.hasChildPage(finalName)) {
        throw new ToolException("Cannot move to '" + dotted(target) + "': that page already exists");
      }
      if (parentPath.startsWith(current)) {
        throw new ToolException("Cannot move '" + dotted(current) + "' below itself or its own child");
      }

      WikiPagePath now = current;
      if (!parentPath.equals(current.parentPath())) {
        String location = parentPath.isEmpty() ? "." : dotted(parentPath);
        run(new MovePageResponder(), dotted(now), "newLocation", location);
        now = parentPath.copy();
        now.addNameToEnd(last(current));
      }
      if (!last(now).equals(finalName)) {
        run(new RenamePageResponder(), dotted(now), "newName", finalName);
        now = target;
      }
      return ToolResult.json(new JSONObject().put("path", dotted(now)));
    }

    private void run(fitnesse.Responder responder, String resource, String input, String value)
        throws ToolException {
      MockRequest request = new MockRequest();
      request.setResource(resource);
      request.addInput(input, value);
      request.addInput("refactorReferences", "on");
      Response response;
      try {
        response = responder.makeResponse(ctx, request);
      } catch (Exception e) {
        throw new ToolException("Move failed: " + e.getMessage());
      }
      if (response.getStatus() != 303) {
        String body = response instanceof SimpleResponse ? ((SimpleResponse) response).getContent() : "";
        String text = body.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
        throw new ToolException("Move failed (HTTP " + response.getStatus() + "): " + text);
      }
    }
  }

  private static final class DeletePage implements Tool {
    private final FitNesseContext ctx;

    DeletePage(FitNesseContext ctx) { this.ctx = ctx; }

    public String name() { return "delete_page"; }

    public String description() {
      return "Deletes a page and all its child pages. The wiki root cannot be deleted. "
          + "Returns {path} of the deleted page. " + PATH_HELP;
    }

    public JSONObject inputSchema() {
      return new JSONObject().put("type", "object")
          .put("properties", new JSONObject().put("path", prop("string", PATH_HELP)))
          .put("required", new JSONArray().put("path"));
    }

    public ToolResult call(JSONObject args) throws ToolException {
      WikiPage page = PagePaths.resolve(ctx.getRootPage(), args.getString("path"));
      if (page.isRoot()) {
        throw new ToolException("Cannot delete the wiki root page; give the path of a page below it");
      }
      String path = PagePaths.fullPath(page);
      page.getParent().removeChildPage(page.getName());
      return ToolResult.json(new JSONObject().put("path", path));
    }
  }
}
