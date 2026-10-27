package io.github.tortaje.wiki;

import static org.assertj.core.api.Assertions.assertThat;

import fitnesse.wiki.PageData;
import fitnesse.wiki.WikiPage;
import fitnesse.wiki.WikiPageProperty;
import io.github.tortaje.protocol.McpProtocol;
import io.github.tortaje.protocol.ToolRegistry;
import io.github.tortaje.testsupport.McpClient;
import io.github.tortaje.testsupport.TestWiki;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WikiReadToolsTest {
  @TempDir Path tmp;
  TestWiki wiki;
  McpClient client;

  @BeforeEach void setUp() {
    wiki = TestWiki.create(tmp);
    ToolRegistry registry = new ToolRegistry();
    WikiReadTools.registerAll(registry, wiki.context());
    client = new McpClient(new McpProtocol(registry, "test"));
  }

  @AfterEach void tearDown() { wiki.close(); }

  static JSONObject args(String... kv) {
    JSONObject o = new JSONObject();
    for (int i = 0; i < kv.length; i += 2) o.put(kv[i], kv[i + 1]);
    return o;
  }

  private void tag(WikiPage page, String tags) throws Exception {
    PageData d = page.getData();
    d.setAttribute(WikiPageProperty.SUITES, tags);
    page.commit(d);
  }

  private void makeSuite() throws Exception {
    wiki.page("MySuite", "!1 Suite");
    WikiPage t = wiki.page("MySuite.MyTest", "|script|\n");
    PageData d = t.getData();
    d.setAttribute(WikiPageProperty.SUITES, "smoke");
    d.setAttribute("Test");
    t.commit(d);
  }

  @Test void getPageReturnsContentAndMetadata() throws Exception {
    makeSuite();
    JSONObject p = client.json(client.callTool("get_page", args("path", "MySuite.MyTest")));
    assertThat(p.getString("path")).isEqualTo("MySuite.MyTest");
    assertThat(p.getString("content")).isEqualTo("|script|\n");
    assertThat(p.getString("type")).isEqualTo("Test");
    assertThat(p.getJSONArray("tags").toList()).containsExactly("smoke");
    assertThat(p.getString("lastModified")).matches("\\d{4}-\\d{2}-\\d{2}T.*Z");
  }

  @Test void getPageListsChildren() throws Exception {
    makeSuite();
    JSONObject p = client.json(client.callTool("get_page", args("path", "MySuite")));
    assertThat(p.getJSONArray("children").toList()).containsExactly("MyTest");
  }

  @Test void getPageOfMissingPageIsError() {
    String msg = client.errorText(client.callTool("get_page", args("path", "Nope")));
    assertThat(msg).contains("'Nope'").contains("closest existing ancestor: 'root'");
  }

  @Test void listPagesRespectsDepth() throws Exception {
    makeSuite();
    JSONArray d1 = client.json(client.callTool("list_pages", args())).getJSONArray("pages");
    assertThat(paths(d1)).containsExactly("MySuite");
    JSONObject a2 = new JSONObject().put("depth", 2);
    JSONArray d2 = client.json(client.callTool("list_pages", a2)).getJSONArray("pages");
    assertThat(paths(d2)).containsExactly("MySuite", "MySuite.MyTest");
    JSONObject test = d2.getJSONObject(1);
    assertThat(test.getString("type")).isEqualTo("Test");
    assertThat(test.getJSONArray("tags").toList()).containsExactly("smoke");
  }

  @Test void listPagesRejectsDepthAbove10() {
    String msg = client.errorText(client.callTool("list_pages", new JSONObject().put("depth", 11)));
    assertThat(msg).contains("10");
  }

  @Test void searchByTitleAndContent() throws Exception {
    wiki.page("CalcSuite", "nothing here");
    wiki.page("OtherPage", "x ".repeat(300) + "the Calc fixture" + " y".repeat(300));
    JSONObject r = client.json(client.callTool("search_pages", args("query", "calc")));
    JSONArray results = r.getJSONArray("results");
    assertThat(r.getBoolean("truncated")).isFalse();
    assertThat(results.length()).isEqualTo(2);
    JSONObject title = results.getJSONObject(0);
    assertThat(title.getString("path")).isEqualTo("CalcSuite");
    assertThat(title.getString("matchedIn")).isEqualTo("title");
    JSONObject content = results.getJSONObject(1);
    assertThat(content.getString("path")).isEqualTo("OtherPage");
    assertThat(content.getString("matchedIn")).isEqualTo("content");
    assertThat(content.getString("excerpt")).containsIgnoringCase("calc");
    assertThat(content.getString("excerpt").length()).isLessThanOrEqualTo(200);
  }

  @Test void searchCapsAt100Results() {
    for (int i = 0; i < 120; i++) wiki.page("Page" + letters(i), "needle");
    JSONObject r = client.json(client.callTool("search_pages", args("query", "needle")));
    assertThat(r.getJSONArray("results").length()).isEqualTo(100);
    assertThat(r.getBoolean("truncated")).isTrue();
  }

  @Test void searchUnderPathOnly() {
    wiki.page("AaaSuite", "x");
    wiki.page("AaaSuite.Inner", "needle");
    wiki.page("Outside", "needle");
    JSONObject r = client.json(client.callTool("search_pages", args("query", "needle", "path", "AaaSuite")));
    assertThat(paths(r.getJSONArray("results"))).containsExactly("AaaSuite.Inner");
  }

  @Test void searchInTitleOnlyExcludesContentMatches() {
    wiki.page("NeedleTitle", "nothing");
    wiki.page("ContentPage", "has a needle inside");
    JSONObject r = client.json(client.callTool("search_pages",
        args("query", "needle", "in", "title")));
    assertThat(paths(r.getJSONArray("results"))).containsExactly("NeedleTitle");
  }

  @Test void searchInContentOnlyExcludesTitleMatches() {
    wiki.page("NeedleTitle", "nothing");
    wiki.page("ContentPage", "has a needle inside");
    JSONObject r = client.json(client.callTool("search_pages",
        args("query", "needle", "in", "content")));
    assertThat(paths(r.getJSONArray("results"))).containsExactly("ContentPage");
  }

  @Test void searchRejectsUnknownInValue() {
    String msg = client.errorText(client.callTool("search_pages",
        args("query", "x", "in", "name")));
    assertThat(msg).contains("title").contains("content").contains("both");
  }

  @Test void searchAndListTerminateOnSymbolicLinkCycles() throws Exception {
    WikiPage loop = wiki.page("LoopSuite", "needle");
    wiki.page("LoopSuite.Inner", "needle");
    PageData d = loop.getData();
    d.getProperties().set(fitnesse.wiki.SymbolicPage.PROPERTY_NAME).set("BackLink", ".LoopSuite");
    loop.commit(d);
    assertThat(wiki.root().getPageCrawler().getPage(
        fitnesse.wiki.PathParser.parse("LoopSuite.BackLink"))).isNotNull();
    JSONObject r = client.json(client.callTool("search_pages", args("query", "needle")));
    assertThat(paths(r.getJSONArray("results"))).containsExactly("LoopSuite", "LoopSuite.Inner");
    JSONArray pages = client.json(client.callTool("list_pages",
        new JSONObject().put("depth", 10))).getJSONArray("pages");
    assertThat(paths(pages)).containsExactly("LoopSuite", "LoopSuite.Inner");
  }

  private static String letters(int i) {
    return IntStream.of(i / 26, i % 26).mapToObj(n -> String.valueOf((char) ('A' + n)))
        .collect(Collectors.joining());
  }

  private static java.util.List<String> paths(JSONArray a) {
    java.util.List<String> out = new java.util.ArrayList<>();
    for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i).getString("path"));
    return out;
  }
}
