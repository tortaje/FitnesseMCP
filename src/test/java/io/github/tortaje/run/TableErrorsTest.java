package io.github.tortaje.run;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TableErrorsTest {
  /** As emitted by FitNesse 20260313 for |script| followed by |check|. */
  static final String BAD_SCRIPT = "<br/><table>\n\t<tr class=\"slimRowTitle\">\n"
      + "\t\t<td>script <span class=\"error\"><strong> script: Bad table! Too few columns in row 2. "
      + "Expected a function in column 2.</strong></span></td>\n\t</tr>\n"
      + "\t<tr class=\"slimRowColor0\">\n\t\t<td>check</td>\n\t</tr>\n</table>\n";

  @Test void extractsErrorSpanTextWithoutTags() {
    assertThat(TableErrors.extract(BAD_SCRIPT))
        .containsExactly("script: Bad table! Too few columns in row 2. Expected a function in column 2.");
  }

  @Test void extractsErrorCellsAndUnescapesEntities() {
    String html = "<td class=\"error\">a &lt;b&gt; &amp; &quot;c&quot; &#39;d&#39;&nbsp;e</td>"
        + "<span class=\"pass\">fine</span><span class=\"error\">  </span>";
    assertThat(TableErrors.extract(html)).containsExactly("a <b> & \"c\" 'd' e");
  }

  @Test void capsEachMessage() {
    String html = "<span class=\"error\">" + "x".repeat(1000) + "</span>";
    assertThat(TableErrors.extract(html).get(0)).hasSize(TableErrors.MAX_MESSAGE_CHARS);
  }

  @Test void nothingToExtract() {
    assertThat(TableErrors.extract("<table><tr><td class=\"pass\">ok</td></tr></table>")).isEmpty();
    assertThat(TableErrors.extract(null)).isEmpty();
  }
}
