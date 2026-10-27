package io.github.tortaje.wiki;

import static org.assertj.core.api.Assertions.assertThat;

import fitnesse.wiki.PathParser;
import fitnesse.wiki.WikiPage;
import io.github.tortaje.protocol.McpProtocol;
import io.github.tortaje.protocol.ToolRegistry;
import io.github.tortaje.testsupport.McpClient;
import io.github.tortaje.testsupport.TestWiki;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WikiWriteToolsTest {
  @TempDir Path tmp;
  TestWiki wiki;
  McpClient client;

  @BeforeEach void setUp() {
    wiki = TestWiki.create(tmp);
    ToolRegistry registry = new ToolRegistry();
    WikiReadTools.registerAll(registry, wiki.context());
    WikiWriteTools.registerAll(registry, wiki.context());
    client = new McpClient(new McpProtocol(registry, "test"));
  }

  @AfterEach void tearDown() { wiki.close(); }

  private JSONObject get(String path) {
    return client.json(client.callTool("get_page", new JSONObject().put("path", path)));
  }

  private boolean exists(String path) {
    return wiki.root().getPageCrawler().getPage(PathParser.parse(path)) != null;
  }

  private JSONObject save(JSONObject args) {
    return client.callTool("save_page", args);
  }

  private JSONObject move(JSONObject args) {
    return client.callTool("move_page", args);
  }

  @Test void createsPageWithTypeAndTags() {
    wiki.page("Suite", "");
    JSONObject r = client.json(save(new JSONObject().put("path", "Suite.NewTest")
        .put("content", "hello").put("type", "Test").put("tags", new JSONArray().put("a").put("b"))));
    assertThat(r.getString("path")).isEqualTo("Suite.NewTest");
    assertThat(r.getString("lastModified")).isNotEmpty();
    JSONObject p = get("Suite.NewTest");
    assertThat(p.getString("type")).isEqualTo("Test");
    assertThat(p.getJSONArray("tags").toList()).containsExactly("a", "b");
    assertThat(p.getString("content")).isEqualTo("hello");
  }

  @Test void missingParentIsError() {
    String msg = client.errorText(save(new JSONObject().put("path", "No.Such.Page").put("content", "x")));
    assertThat(msg).contains("createParents");
    assertThat(exists("No")).isFalse();
  }

  @Test void createParentsCreatesChain() {
    client.json(save(new JSONObject().put("path", "No.Such.Page").put("content", "x")
        .put("createParents", true)));
    assertThat(exists("No")).isTrue();
    assertThat(exists("No.Such")).isTrue();
    assertThat(exists("No.Such.Page")).isTrue();
  }

  @Test void updateKeepsTypeWhenOmitted() {
    client.json(save(new JSONObject().put("path", "MySuite").put("content", "a").put("type", "Suite")));
    client.json(save(new JSONObject().put("path", "MySuite").put("content", "b")));
    JSONObject p = get("MySuite");
    assertThat(p.getString("type")).isEqualTo("Suite");
    assertThat(p.getString("content")).isEqualTo("b");
  }

  @Test void typeStaticClearsTestAndSuite() {
    client.json(save(new JSONObject().put("path", "MySuite").put("content", "a").put("type", "Suite")));
    client.json(save(new JSONObject().put("path", "MySuite").put("content", "a").put("type", "Static")));
    assertThat(get("MySuite").getString("type")).isEqualTo("Static");
  }

  @Test void staleExpectedLastModifiedIsConflict() {
    client.json(save(new JSONObject().put("path", "PageOne").put("content", "old")));
    String current = get("PageOne").getString("lastModified");
    String msg = client.errorText(save(new JSONObject().put("path", "PageOne").put("content", "new")
        .put("expectedLastModified", "2000-01-01T00:00:00Z")));
    assertThat(msg).contains(current);
    assertThat(get("PageOne").getString("content")).isEqualTo("old");
  }

  @Test void matchingExpectedLastModifiedSaves() {
    client.json(save(new JSONObject().put("path", "PageOne").put("content", "old")));
    String current = get("PageOne").getString("lastModified");
    client.json(save(new JSONObject().put("path", "PageOne").put("content", "new")
        .put("expectedLastModified", current)));
    assertThat(get("PageOne").getString("content")).isEqualTo("new");
  }

  @Test void contentRoundTrips() {
    client.json(save(new JSONObject().put("path", "PageOne").put("content", "a\r\nb\n")));
    assertThat(get("PageOne").getString("content")).isEqualTo("a\nb\n");
  }

  @Test void saveRootRefused() {
    assertThat(client.errorText(save(new JSONObject().put("path", "").put("content", "x"))))
        .contains("root");
  }

  @Test void invalidTypeIsError() {
    assertThat(client.errorText(save(new JSONObject().put("path", "PageOne").put("content", "x")
        .put("type", "Bogus")))).contains("Test").contains("Suite").contains("Static");
  }

  @Test void movesToNewParentAndUpdatesReferences() {
    wiki.page("AlphaPage", "");
    wiki.page("AlphaPage.TargetPage", "target");
    wiki.page("BetaPage", "");
    wiki.page("OtherPage", "see .AlphaPage.TargetPage here");
    JSONObject r = client.json(move(new JSONObject().put("path", "AlphaPage.TargetPage").put("newParent", "BetaPage")));
    assertThat(r.getString("path")).isEqualTo("BetaPage.TargetPage");
    assertThat(exists("BetaPage.TargetPage")).isTrue();
    assertThat(exists("AlphaPage.TargetPage")).isFalse();
    assertThat(get("OtherPage").getString("content")).contains(".BetaPage.TargetPage");
  }

  @Test void movesToRoot() {
    wiki.page("AlphaPage", "");
    wiki.page("AlphaPage.TargetPage", "target");
    JSONObject r = client.json(move(new JSONObject().put("path", "AlphaPage.TargetPage").put("newParent", "root")));
    assertThat(r.getString("path")).isEqualTo("TargetPage");
    assertThat(exists("TargetPage")).isTrue();
  }

  @Test void renames() {
    wiki.page("AlphaPage", "");
    wiki.page("AlphaPage.TargetPage", "target");
    JSONObject r = client.json(move(new JSONObject().put("path", "AlphaPage.TargetPage").put("newName", "Renamed")));
    assertThat(r.getString("path")).isEqualTo("AlphaPage.Renamed");
    assertThat(exists("AlphaPage.Renamed")).isTrue();
    assertThat(exists("AlphaPage.TargetPage")).isFalse();
  }

  @Test void movesAndRenamesTogether() {
    wiki.page("AlphaPage", "");
    wiki.page("AlphaPage.TargetPage", "target");
    wiki.page("BetaPage", "");
    JSONObject r = client.json(move(new JSONObject().put("path", "AlphaPage.TargetPage")
        .put("newParent", "BetaPage").put("newName", "Renamed")));
    assertThat(r.getString("path")).isEqualTo("BetaPage.Renamed");
    assertThat(exists("AlphaPage.TargetPage")).isFalse();
  }

  @Test void moveRequiresParentOrName() {
    wiki.page("AlphaPage", "");
    assertThat(client.errorText(move(new JSONObject().put("path", "AlphaPage"))))
        .contains("newParent").contains("newName");
  }

  @Test void moveOntoExistingIsError() {
    wiki.page("AlphaPage", "");
    wiki.page("AlphaPage.TargetPage", "t");
    wiki.page("BetaPage", "");
    wiki.page("BetaPage.TargetPage", "other");
    assertThat(client.errorText(move(new JSONObject().put("path", "AlphaPage.TargetPage").put("newParent", "BetaPage"))))
        .contains("already exists");
    assertThat(exists("AlphaPage.TargetPage")).isTrue();
  }

  @Test void moveToMissingParentIsError() {
    wiki.page("AlphaPage", "");
    assertThat(client.errorText(move(new JSONObject().put("path", "AlphaPage").put("newParent", "Nope"))))
        .contains("Nope");
  }

  @Test void moveMissingPageIsError() {
    assertThat(client.errorText(move(new JSONObject().put("path", "Nope").put("newName", "Other"))))
        .contains("does not exist");
  }

  @Test void renameToInvalidNameIsError() {
    wiki.page("AlphaPage", "");
    assertThat(client.errorText(move(new JSONObject().put("path", "AlphaPage").put("newName", "lower"))))
        .contains("capitalised");
  }

  @Test void deletesAlphaPagendChildren() {
    wiki.page("AlphaPage", "");
    wiki.page("AlphaPage.TargetPage", "t");
    client.json(client.callTool("delete_page", new JSONObject().put("path", "AlphaPage")));
    assertThat(exists("AlphaPage")).isFalse();
    assertThat(exists("AlphaPage.TargetPage")).isFalse();
  }

  @Test void deleteRootRefused() {
    assertThat(client.errorText(client.callTool("delete_page", new JSONObject().put("path", ""))))
        .contains("root");
  }

  @Test void filesIsReservedForAttachments() {
    for (String path : new String[] {"files", "Files.MyPage", "FILES", ".files.x"}) {
      String error = client.errorText(save(new JSONObject().put("path", path).put("content", "x")
          .put("createParents", true)));
      assertThat(error).as(path).contains("reserved").contains("upload_file");
    }
    assertThat(java.nio.file.Files.exists(Path.of(wiki.context().getRootPagePath(), "files", "content.txt")))
        .isFalse();
    assertThat(client.json(save(new JSONObject().put("path", "FrontPage.files").put("content", "x")
        .put("createParents", true))).getString("path")).isEqualTo("FrontPage.files");
  }

  @Test void moveCannotTargetReservedFilesName() {
    client.json(save(new JSONObject().put("path", "TopPage").put("content", "x")));
    client.json(save(new JSONObject().put("path", "AlphaPage.ChildPage").put("content", "y")
        .put("createParents", true)));

    for (JSONObject move : new JSONObject[] {
        new JSONObject().put("path", "TopPage").put("newName", "Files"),
        new JSONObject().put("path", "AlphaPage.ChildPage").put("newParent", "root").put("newName", "Files")}) {
      String error = client.errorText(client.callTool("move_page", move));
      assertThat(error).as(move.toString()).contains("reserved").contains("upload_file");
    }
    assertThat(java.nio.file.Files.exists(Path.of(wiki.context().getRootPagePath(), "files", "content.txt")))
        .isFalse();
    assertThat(client.json(client.callTool("move_page", new JSONObject().put("path", "AlphaPage.ChildPage")
        .put("newName", "Files"))).getString("path")).isEqualTo("AlphaPage.Files");
  }

  @Test void pageMissingAfterSaveIsAToolError() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> WikiWriteTools.requireSaved(null, PathParser.parse("Some.Page")))
        .isInstanceOf(io.github.tortaje.protocol.ToolException.class)
        .hasMessageContaining("Some.Page").hasMessageContaining("get_page");
  }
}
