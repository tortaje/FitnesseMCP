package io.github.tortaje.wiki;

import static org.assertj.core.api.Assertions.assertThat;

import fitnesse.wiki.PageData;
import fitnesse.wiki.WikiPage;
import fitnesse.wiki.WikiPageProperty;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;

class PageJsonTest {
  /** A WikiPage whose only working method is getData(), backed by the given properties. */
  private static WikiPage pageWith(WikiPageProperty props) {
    PageData data = new PageData("", props);
    return (WikiPage) Proxy.newProxyInstance(WikiPage.class.getClassLoader(),
        new Class<?>[] {WikiPage.class}, (proxy, method, args) -> {
          if (method.getName().equals("getData")) return data;
          throw new UnsupportedOperationException(method.getName());
        });
  }

  @Test void missingModificationTimeIsNullNotNow() {
    assertThat(PageJson.lastModified(pageWith(new WikiPageProperty()))).isNull();
  }

  @Test void unparsableModificationTimeIsNull() {
    WikiPageProperty props = new WikiPageProperty();
    props.set(WikiPageProperty.LAST_MODIFIED, "not a date");
    assertThat(PageJson.lastModified(pageWith(props))).isNull();
  }

  @Test void recordedModificationTimeIsIsoInstant() {
    WikiPageProperty props = new WikiPageProperty();
    props.set(WikiPageProperty.LAST_MODIFIED, "20260102030405");
    assertThat(PageJson.lastModified(pageWith(props))).matches("2026-01-0\\dT.*Z");
  }
}
