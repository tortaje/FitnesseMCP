package io.github.tortaje.files;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tortaje.protocol.McpProtocol;
import io.github.tortaje.protocol.ToolRegistry;
import io.github.tortaje.testsupport.McpClient;
import io.github.tortaje.testsupport.TestWiki;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileToolsTest {
  static final String PNG_B64 =
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNgYGD4DwABBAEAHnOcQAAAAABJRU5ErkJggg==";

  @TempDir Path tmp;
  Path wikiDir;
  Path outside;
  TestWiki wiki;
  McpClient client;

  @BeforeEach void setUp() throws IOException {
    wikiDir = Files.createDirectory(tmp.resolve("wiki"));
    outside = Files.createDirectory(tmp.resolve("outside"));
    wiki = TestWiki.create(wikiDir);
    ToolRegistry registry = new ToolRegistry();
    FileTools.registerAll(registry, wiki.context());
    client = new McpClient(new McpProtocol(registry, "test"));
  }

  @AfterEach void tearDown() { wiki.close(); }

  private Path filesDir() { return Path.of(wiki.context().getRootPagePath(), "files"); }

  private JSONObject uploadNothing(String path) {
    return client.callTool("upload_file", new JSONObject().put("path", path));
  }

  private JSONObject uploadText(String path, String content) {
    return client.callTool("upload_file", new JSONObject().put("path", path).put("content", content));
  }

  private JSONObject uploadBytes(String path, byte[] bytes) {
    return client.callTool("upload_file", new JSONObject().put("path", path)
        .put("contentBase64", Base64.getEncoder().encodeToString(bytes)));
  }

  @Test void listOnMissingFilesDirIsEmpty() {
    JSONObject out = client.json(client.callTool("list_files", new JSONObject()));
    assertThat(out.getJSONArray("files").length()).isZero();
  }

  @Test void uploadTextThenList() {
    client.json(uploadText("docs/a.txt", "hi"));
    JSONArray files = client.json(client.callTool("list_files",
        new JSONObject().put("dir", "docs"))).getJSONArray("files");
    assertThat(files.length()).isEqualTo(1);
    JSONObject f = files.getJSONObject(0);
    assertThat(f.getString("name")).isEqualTo("a.txt");
    assertThat(f.getString("kind")).isEqualTo("file");
    assertThat(f.getLong("size")).isEqualTo(2);
    assertThat(java.time.Instant.parse(f.getString("lastModified"))).isNotNull();
    JSONArray top = client.json(client.callTool("list_files", new JSONObject()))
        .getJSONArray("files");
    assertThat(top.getJSONObject(0).getString("name")).isEqualTo("docs");
    assertThat(top.getJSONObject(0).getString("kind")).isEqualTo("dir");
  }

  @Test void getTextFile() {
    uploadText("docs/a.txt", "hi");
    JSONObject out = client.json(client.callTool("get_file", new JSONObject().put("path", "docs/a.txt")));
    assertThat(out.getString("path")).isEqualTo("docs/a.txt");
    assertThat(out.getString("content")).isEqualTo("hi");
  }

  @Test void getImageReturnsImageContent() {
    client.json(client.callTool("upload_file", new JSONObject().put("path", "img/p.png")
        .put("contentBase64", PNG_B64)));
    JSONObject res = client.callTool("get_file", new JSONObject().put("path", "img/p.png"));
    assertThat(res.getBoolean("isError")).isFalse();
    JSONObject item = res.getJSONArray("content").getJSONObject(0);
    assertThat(item.getString("type")).isEqualTo("image");
    assertThat(item.getString("mimeType")).isEqualTo("image/png");
    assertThat(item.getString("data")).isEqualTo(PNG_B64);
  }

  @Test void getOtherBinaryReturnsMetadata() {
    uploadBytes("bin/x.dat", new byte[] {0, 1, 2});
    JSONObject out = client.json(client.callTool("get_file", new JSONObject().put("path", "bin/x.dat")));
    assertThat(out.getString("path")).isEqualTo("bin/x.dat");
    assertThat(out.getLong("size")).isEqualTo(3);
    assertThat(out.getString("url")).isEqualTo("/files/bin/x.dat");
    assertThat(out.has("content")).isFalse();
  }

  @Test void invalidUtf8IsTreatedAsBinary() {
    uploadBytes("bin/y.txt", new byte[] {(byte) 0xC3, (byte) 0x28});
    JSONObject out = client.json(client.callTool("get_file", new JSONObject().put("path", "bin/y.txt")));
    assertThat(out.has("content")).isFalse();
    assertThat(out.getLong("size")).isEqualTo(2);
  }

  @Test void refusesOverwriteByDefault() {
    uploadText("a.txt", "one");
    assertThat(client.errorText(uploadText("a.txt", "two"))).contains("overwrite");
    JSONObject out = client.json(client.callTool("get_file", new JSONObject().put("path", "a.txt")));
    assertThat(out.getString("content")).isEqualTo("one");
  }

  @Test void overwriteWhenAsked() {
    uploadText("a.txt", "one");
    client.json(client.callTool("upload_file", new JSONObject().put("path", "a.txt")
        .put("content", "two").put("overwrite", true)));
    JSONObject out = client.json(client.callTool("get_file", new JSONObject().put("path", "a.txt")));
    assertThat(out.getString("content")).isEqualTo("two");
  }

  @Test void requiresExactlyOneContentField() {
    assertThat(client.errorText(client.callTool("upload_file", new JSONObject()
        .put("path", "a.txt").put("content", "x").put("contentBase64", "eA==")))).contains("exactly one");
    assertThat(client.errorText(uploadNothing("a.txt"))).contains("exactly one");
  }

  @Test void rejectsInvalidBase64() {
    assertThat(client.errorText(client.callTool("upload_file", new JSONObject()
        .put("path", "a.txt").put("contentBase64", "!!not base64!!")))).contains("base64");
  }

  @Test void rejectsTooLarge() {
    assertThat(client.errorText(uploadBytes("big.bin", new byte[FileTools.MAX_UPLOAD_BYTES + 1])))
        .contains("10 MiB");
    assertThat(client.errorText(uploadText("big.txt", "x".repeat(FileTools.MAX_UPLOAD_BYTES + 1))))
        .contains("10 MiB");
    assertThat(filesDir().resolve("big.bin")).doesNotExist();
  }

  @Test void acceptsExactlyTheLimit() {
    client.json(uploadBytes("max.bin", new byte[FileTools.MAX_UPLOAD_BYTES]));
    assertThat(filesDir().resolve("max.bin")).hasSize(FileTools.MAX_UPLOAD_BYTES);
  }

  @Test void rejectsTraversal() {
    for (String p : new String[] {"../x.txt", "/etc/passwd", "a/../../x", "C:\\x", "a\\..\\..\\x"}) {
      assertThat(client.errorText(uploadText(p, "x"))).as(p).isNotBlank();
      assertThat(client.errorText(client.callTool("get_file", new JSONObject().put("path", p)))).as(p).isNotBlank();
      assertThat(client.errorText(client.callTool("delete_file", new JSONObject().put("path", p)))).as(p).isNotBlank();
    }
    assertThat(client.errorText(client.callTool("list_files", new JSONObject().put("dir", "..")))).isNotBlank();
    assertThat(wikiDir.resolve("x.txt")).doesNotExist();
  }

  @Test void emptyPathIsRejectedForFileOperations() {
    assertThat(client.errorText(uploadText("", "x"))).contains("path");
    assertThat(client.errorText(uploadText(".", "x"))).contains("path");
    assertThat(client.errorText(client.callTool("delete_file", new JSONObject().put("path", "")))).contains("path");
  }

  @Test void rejectsSymlinkEscape() throws IOException {
    Files.createDirectories(filesDir());
    Files.createSymbolicLink(filesDir().resolve("link"), outside);
    assertThat(client.errorText(uploadText("link/x.txt", "x"))).contains("symbolic link");
    assertThat(outside.resolve("x.txt")).doesNotExist();
    Files.writeString(outside.resolve("secret.txt"), "secret");
    assertThat(client.errorText(client.callTool("get_file", new JSONObject().put("path", "link/secret.txt")))).contains("symbolic link");
    assertThat(client.errorText(client.callTool("list_files", new JSONObject().put("dir", "link")))).contains("symbolic link");
    assertThat(client.errorText(client.callTool("delete_file", new JSONObject().put("path", "link/secret.txt")))).contains("symbolic link");
    assertThat(outside.resolve("secret.txt")).exists();
  }

  @Test void rejectsSymlinkedFinalComponent() throws IOException {
    Files.createDirectories(filesDir());
    Files.writeString(outside.resolve("target.txt"), "orig");
    Files.createSymbolicLink(filesDir().resolve("f.txt"), outside.resolve("target.txt"));
    JSONObject res = client.callTool("upload_file", new JSONObject().put("path", "f.txt")
        .put("content", "evil").put("overwrite", true));
    assertThat(client.errorText(res)).contains("symbolic link");
    assertThat(Files.readString(outside.resolve("target.txt"))).isEqualTo("orig");
    assertThat(client.errorText(client.callTool("delete_file", new JSONObject().put("path", "f.txt")))).contains("symbolic link");
  }

  @Test void rejectsDanglingSymlinkWrite() throws IOException {
    Files.createDirectories(filesDir());
    Files.createSymbolicLink(filesDir().resolve("d.txt"), outside.resolve("not-yet.txt"));
    assertThat(client.errorText(uploadText("d.txt", "x"))).contains("symbolic link");
    assertThat(outside.resolve("not-yet.txt")).doesNotExist();
  }

  @Test void symlinkInsideFilesIsAllowed() throws IOException {
    uploadText("real/a.txt", "ok");
    Files.createSymbolicLink(filesDir().resolve("alias"), filesDir().resolve("real"));
    JSONObject out = client.json(client.callTool("get_file", new JSONObject().put("path", "alias/a.txt")));
    assertThat(out.getString("content")).isEqualTo("ok");
  }

  @Test void deleteFileAndEmptyDir() {
    uploadText("docs/a.txt", "hi");
    assertThat(client.errorText(client.callTool("delete_file", new JSONObject().put("path", "docs")))).contains("not empty");
    client.json(client.callTool("delete_file", new JSONObject().put("path", "docs/a.txt")));
    client.json(client.callTool("delete_file", new JSONObject().put("path", "docs")));
    assertThat(filesDir().resolve("docs")).doesNotExist();
    assertThat(client.errorText(client.callTool("delete_file", new JSONObject().put("path", "docs")))).contains("does not exist");
  }

  @Test void listShowsSymlinksAsLinksWithoutFollowing() throws IOException {
    Files.createDirectories(filesDir());
    Files.write(outside.resolve("big.bin"), new byte[1234]);
    Files.createSymbolicLink(filesDir().resolve("dangling"), outside.resolve("missing"));
    Files.createSymbolicLink(filesDir().resolve("out"), outside.resolve("big.bin"));
    JSONArray files = client.json(client.callTool("list_files", new JSONObject()))
        .getJSONArray("files");
    assertThat(files.length()).isEqualTo(2);
    for (int i = 0; i < 2; i++) {
      assertThat(files.getJSONObject(i).getString("kind")).isEqualTo("link");
      assertThat(files.getJSONObject(i).has("size")).isFalse();
    }
  }

  @Test void parentThatIsAFileGivesClearMessageWithoutAbsolutePath() {
    uploadText("a.txt", "x");
    String msg = client.errorText(uploadText("a.txt/b", "y"));
    assertThat(msg).contains("a.txt").doesNotContain(filesDir().toString());
  }

  @Test void acceptsLineWrappedBase64() {
    String wrapped = PNG_B64.replaceAll("(.{76})", "$1\r\n");
    client.json(client.callTool("upload_file", new JSONObject().put("path", "w.png")
        .put("contentBase64", wrapped)));
    assertThat(Base64.getEncoder().encodeToString(readBytes("w.png"))).isEqualTo(PNG_B64);
  }

  private byte[] readBytes(String rel) {
    try {
      return Files.readAllBytes(filesDir().resolve(rel));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test void missingDirectoryMessageNamesTheDirArgument() {
    String error = client.errorText(client.callTool("list_files", new JSONObject().put("dir", "nope")));
    assertThat(error).contains("call list_files with no `dir`");
  }

  @Test void urlEncodesEachPathSegment() {
    uploadBytes("my dir/a b#1%.dat", new byte[] {0, 1, 2});
    JSONObject out = client.json(client.callTool("get_file", new JSONObject().put("path", "my dir/a b#1%.dat")));
    assertThat(out.getString("url")).isEqualTo("/files/my%20dir/a%20b%231%25.dat");
    JSONObject uploaded = client.json(client.callTool("upload_file", new JSONObject()
        .put("path", "my dir/é.txt").put("content", "x")));
    assertThat(uploaded.getString("url")).isEqualTo("/files/my%20dir/%C3%A9.txt");
  }
}
