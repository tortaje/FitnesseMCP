package io.github.tortaje.wiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fitnesse.wiki.WikiPage;
import io.github.tortaje.protocol.ToolException;
import io.github.tortaje.testsupport.TestWiki;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PagePathsTest {
  @TempDir Path tmp;
  TestWiki wiki;

  @BeforeEach void setUp() {
    wiki = TestWiki.create(tmp);
    wiki.page("FrontPage", "front");
    wiki.page("FrontPage.X", "x");
  }

  @AfterEach void tearDown() { wiki.close(); }

  @Test void emptyAndRootResolveToRoot() throws Exception {
    WikiPage root = wiki.root();
    assertThat(PagePaths.resolve(root, "")).isSameAs(root);
    assertThat(PagePaths.resolve(root, "  ")).isSameAs(root);
    assertThat(PagePaths.resolve(root, "root")).isSameAs(root);
  }

  @Test void leadingDotIsStripped() throws Exception {
    WikiPage page = PagePaths.resolve(wiki.root(), ".FrontPage.X");
    assertThat(PagePaths.fullPath(page)).isEqualTo("FrontPage.X");
    assertThat(page.getName()).isEqualTo("X");
  }

  @Test void trailingDotIsInvalid() {
    assertThatThrownBy(() -> PagePaths.resolve(wiki.root(), "FrontPage.X."))
        .isInstanceOf(ToolException.class)
        .hasMessage("Invalid page path 'FrontPage.X.': use dot-separated WikiWords like FrontPage.MySuite");
  }

  @Test void lowercaseNameNeverFailsWithAnythingButAToolException() {
    // Resolves on case-insensitive file systems, reports a missing page elsewhere.
    try {
      PagePaths.resolve(wiki.root(), "frontpage");
    } catch (ToolException e) {
      assertThat(e).hasMessageContaining("Page 'frontpage' does not exist");
    }
  }

  @Test void missingPageNamesRootAsClosestAncestor() {
    assertThatThrownBy(() -> PagePaths.resolve(wiki.root(), "Missing.Child"))
        .isInstanceOf(ToolException.class)
        .hasMessage("Page 'Missing.Child' does not exist; closest existing ancestor: 'root'");
  }

  @Test void missingPageNamesExistingAncestor() {
    assertThatThrownBy(() -> PagePaths.resolve(wiki.root(), "FrontPage.Missing"))
        .isInstanceOf(ToolException.class)
        .hasMessage("Page 'FrontPage.Missing' does not exist; closest existing ancestor: 'FrontPage'");
  }

  @Test void fullPathOfRootIsRoot() {
    assertThat(PagePaths.fullPath(wiki.root())).isEqualTo("root");
  }
}
