package io.github.tortaje.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tortaje.testsupport.TestWiki;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportScannerTest {
  @TempDir Path tmp;

  @Test void readsImportTable() {
    assertThat(ImportScanner.packagesFrom("|import|\n|demo|\n|com.acme|\n\n|script|"))
        .containsExactlyInAnyOrder("demo", "com.acme");
  }

  @Test void caseInsensitiveAndIgnoresOtherTables() {
    assertThat(ImportScanner.packagesFrom("|Import|\n|demo|\n")).containsExactly("demo");
    assertThat(ImportScanner.packagesFrom("|script|\n|demo|\n")).isEmpty();
  }

  @Test void recognisesTableModifierPrefixes() {
    assertThat(ImportScanner.packagesFrom("!|Import|\n|demo|\n|com.acme|\n")).containsExactlyInAnyOrder("demo", "com.acme");
    assertThat(ImportScanner.packagesFrom("-|import|\n-|demo|\n")).containsExactly("demo");
    assertThat(ImportScanner.packagesFrom("-!|import|\n-!|demo|\n")).containsExactly("demo");
  }

  @Test void importTableEndsAtBlankLineEvenBeforeWiderTable() {
    assertThat(ImportScanner.packagesFrom("!|import|\n|demo|\n\n!|script|x|\n|other|y|\n"))
        .containsExactly("demo");
  }

  @Test void unescapesLiteralCells() {
    assertThat(ImportScanner.packagesFrom("|import|\n|!-demo-!|\n")).containsExactly("demo");
  }

  @Test void packagesForIncludesSetUpAndAncestors() {
    try (TestWiki wiki = TestWiki.create(tmp)) {
      wiki.page("Suite", "|import|\n|from.suite|\n");
      wiki.page("Suite.SetUp", "|import|\n|from.setup|\n");
      var test = wiki.testPage("Suite.Test", "content");
      assertThat(ImportScanner.packagesFor(test)).containsExactlyInAnyOrder("from.suite", "from.setup");
    }
  }
}
