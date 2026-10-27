package io.github.tortaje.files;

import fitnesse.FitNesseContext;
import io.github.tortaje.protocol.Tool;
import io.github.tortaje.protocol.ToolException;
import io.github.tortaje.protocol.ToolRegistry;
import io.github.tortaje.protocol.ToolResult;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** File tools over FitNesseRoot/files: list_files, get_file, upload_file and delete_file. */
public final class FileTools {
  public static final int MAX_UPLOAD_BYTES = 10 * 1024 * 1024;

  private static final Map<String, String> IMAGE_TYPES = Map.of(
      "png", "image/png", "jpg", "image/jpeg", "jpeg", "image/jpeg",
      "gif", "image/gif", "webp", "image/webp");

  private static final String PATH_HELP = "Path relative to the wiki's files directory "
      + "(FitNesseRoot/files), '/'-separated, e.g. docs/readme.txt. No '..' or absolute paths.";

  private FileTools() {}

  public static void registerAll(ToolRegistry r, FitNesseContext ctx) {
    FilesRoot root = new FilesRoot(Path.of(ctx.getRootPagePath(), "files"));
    r.register(new ListFiles(root));
    r.register(new GetFile(root));
    r.register(new UploadFile(root));
    r.register(new DeleteFile(root));
  }

  private static JSONObject pathProp(String description) {
    return new JSONObject().put("type", "string").put("description", description);
  }

  private static String requiredPath(FilesRoot root, Path resolved)
      throws ToolException {
    if (resolved.equals(root.dir())) {
      throw new ToolException("Argument 'path' must name a file or directory inside the files "
          + "directory, not the directory itself");
    }
    return root.relativize(resolved);
  }

  private static ToolException io(String action, String path, IOException e) {
    if (e instanceof FileAlreadyExistsException) {
      return new ToolException("Cannot " + action + " '" + path + "': it, or a parent of it, "
          + "already exists as a file: choose another path");
    }
    if (e instanceof NotDirectoryException) {
      return new ToolException("Cannot " + action + " '" + path + "': it is not a directory");
    }
    if (e instanceof AccessDeniedException) {
      return new ToolException("Cannot " + action + " '" + path + "': access denied by the "
          + "server's file system permissions");
    }
    if (e instanceof DirectoryNotEmptyException) {
      return new ToolException("Cannot " + action + " '" + path + "': the directory is not "
          + "empty, delete its entries first");
    }
    return new ToolException("Cannot " + action + " '" + path + "': "
        + e.getClass().getSimpleName());
  }

  private static final class ListFiles implements Tool {
    private final FilesRoot root;

    ListFiles(FilesRoot root) { this.root = root; }

    public String name() { return "list_files"; }

    public String description() {
      return "Lists the entries of a directory in the wiki's files directory as "
          + "{files:[{name,kind:'file'|'dir'|'link',size?,lastModified}]}, sorted by name; size "
          + "(bytes) is given for files only, symbolic links are reported as 'link' and not followed, lastModified is ISO-8601. 'dir' defaults to the "
          + "files directory itself. " + PATH_HELP;
    }

    public JSONObject inputSchema() {
      return new JSONObject().put("type", "object").put("properties", new JSONObject()
          .put("dir", pathProp("Directory, " + PATH_HELP + " Defaults to the files directory.")));
    }

    public ToolResult call(JSONObject args) throws ToolException {
      Path dir = root.resolve(args.optString("dir", ""));
      JSONArray out = new JSONArray();
      if (!Files.exists(dir)) {
        if (dir.equals(root.dir())) return ToolResult.json(new JSONObject().put("files", out));
        throw new ToolException("Directory '" + root.relativize(dir) + "' does not exist: "
            + "call list_files with no `dir` to see what exists");
      }
      if (!Files.isDirectory(dir)) {
        throw new ToolException("'" + root.relativize(dir) + "' is a file, not a directory: "
            + "use get_file to read it");
      }
      List<Path> entries = new ArrayList<>();
      try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
        ds.forEach(entries::add);
        entries.sort(Comparator.comparing(p -> p.getFileName().toString()));
        for (Path p : entries) {
          BasicFileAttributes a =
              Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
          String kind = a.isSymbolicLink() ? "link" : a.isDirectory() ? "dir" : "file";
          JSONObject o = new JSONObject().put("name", p.getFileName().toString())
              .put("kind", kind);
          if (kind.equals("file")) o.put("size", a.size());
          o.put("lastModified", a.lastModifiedTime().toInstant().toString());
          out.put(o);
        }
      } catch (IOException e) {
        throw io("list", root.relativize(dir), e);
      }
      return ToolResult.json(new JSONObject().put("files", out));
    }
  }

  private static final class GetFile implements Tool {
    private final FilesRoot root;

    GetFile(FilesRoot root) { this.root = root; }

    public String name() { return "get_file"; }

    public String description() {
      return "Reads a file from the wiki's files directory. Text (UTF-8) files return "
          + "{path,content}; png, jpg, gif and webp files return an MCP image item; other "
          + "binary files, and files over 10 MiB, return {path,size,url} where url is the "
          + "wiki URL of the file. " + PATH_HELP;
    }

    public JSONObject inputSchema() {
      return new JSONObject().put("type", "object")
          .put("properties", new JSONObject().put("path", pathProp(PATH_HELP)))
          .put("required", new JSONArray().put("path"));
    }

    public ToolResult call(JSONObject args) throws ToolException {
      Path file = root.resolve(args.getString("path"));
      String rel = requiredPath(root, file);
      if (!Files.exists(file)) {
        throw new ToolException("File '" + rel + "' does not exist: call list_files to see "
            + "what exists");
      }
      if (Files.isDirectory(file)) {
        throw new ToolException("'" + rel + "' is a directory: use list_files to see its entries");
      }
      try {
        long size = Files.size(file);
        JSONObject meta = new JSONObject().put("path", rel).put("size", size)
            .put("url", url(rel));
        if (size > MAX_UPLOAD_BYTES) return ToolResult.json(meta);
        byte[] bytes = Files.readAllBytes(file);
        String image = IMAGE_TYPES.get(extension(rel));
        if (image != null) return ToolResult.image(bytes, image);
        String text = decodeText(bytes);
        if (text == null) return ToolResult.json(meta);
        return ToolResult.json(new JSONObject().put("path", rel).put("content", text));
      } catch (IOException e) {
        throw io("read", rel, e);
      }
    }

    private static String extension(String rel) {
      int dot = rel.lastIndexOf('.');
      return dot < 0 ? "" : rel.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** The bytes as text when they are strict UTF-8 without NUL, otherwise null. */
    private static String decodeText(byte[] bytes) {
      for (byte b : bytes) if (b == 0) return null;
      try {
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString();
      } catch (CharacterCodingException e) {
        return null;
      }
    }
  }

  private static final class UploadFile implements Tool {
    private final FilesRoot root;

    UploadFile(FilesRoot root) { this.root = root; }

    public String name() { return "upload_file"; }

    public String description() {
      return "Writes a file into the wiki's files directory, creating parent directories. "
          + "Give exactly one of 'content' (UTF-8 text) or 'contentBase64' (binary); at most "
          + "10 MiB. An existing file is only replaced when 'overwrite' is true. Returns "
          + "{path,size,url}. " + PATH_HELP;
    }

    public JSONObject inputSchema() {
      return new JSONObject().put("type", "object").put("properties", new JSONObject()
          .put("path", pathProp(PATH_HELP))
          .put("content", new JSONObject().put("type", "string")
              .put("description", "Text content, written as UTF-8. Use this or contentBase64."))
          .put("contentBase64", new JSONObject().put("type", "string")
              .put("description", "Base64-encoded bytes. Use this or content."))
          .put("overwrite", new JSONObject().put("type", "boolean")
              .put("description", "Replace an existing file (default false).")))
          .put("required", new JSONArray().put("path"));
    }

    public ToolResult call(JSONObject args) throws ToolException {
      boolean hasText = args.has("content") && !args.isNull("content");
      boolean hasB64 = args.has("contentBase64") && !args.isNull("contentBase64");
      if (hasText == hasB64) {
        throw new ToolException("Give exactly one of 'content' (text) or 'contentBase64' "
            + "(binary), " + (hasText ? "not both" : "you gave neither"));
      }
      byte[] data = hasText ? textBytes(args.getString("content"))
          : decodeBase64(args.getString("contentBase64"));
      Path file = root.resolve(args.getString("path"));
      String rel = requiredPath(root, file);
      boolean overwrite = args.optBoolean("overwrite", false);
      try {
        if (Files.isDirectory(file)) {
          throw new ToolException("'" + rel + "' is an existing directory: choose a file path");
        }
        if (Files.exists(file) && !overwrite) {
          throw new ToolException("File '" + rel + "' already exists: set overwrite to true to "
              + "replace it, or choose another path");
        }
        Files.createDirectories(file.getParent());
        root.resolve(rel); // re-check confinement now that the parents exist
        Files.write(file, data);
      } catch (IOException e) {
        throw io("write", rel, e);
      }
      return ToolResult.json(new JSONObject().put("path", rel).put("size", data.length)
          .put("url", url(rel)));
    }

    private static byte[] textBytes(String text) throws ToolException {
      byte[] data = text.getBytes(StandardCharsets.UTF_8);
      return checkSize(data);
    }

    private static byte[] decodeBase64(String b64) throws ToolException {
      b64 = b64.replaceAll("\\s", "");
      if (b64.length() / 4L * 3 > MAX_UPLOAD_BYTES + 3) throw tooLarge();
      try {
        return checkSize(Base64.getDecoder().decode(b64));
      } catch (IllegalArgumentException e) {
        throw new ToolException("Argument 'contentBase64' is not valid base64: "
            + e.getMessage());
      }
    }

    private static byte[] checkSize(byte[] data) throws ToolException {
      if (data.length > MAX_UPLOAD_BYTES) throw tooLarge();
      return data;
    }

    private static ToolException tooLarge() {
      return new ToolException("Content is larger than the 10 MiB limit: upload a smaller file");
    }
  }

  private static final class DeleteFile implements Tool {
    private final FilesRoot root;

    DeleteFile(FilesRoot root) { this.root = root; }

    public String name() { return "delete_file"; }

    public String description() {
      return "Deletes a file, or an empty directory, from the wiki's files directory. "
          + "Returns {deleted:path}. " + PATH_HELP;
    }

    public JSONObject inputSchema() {
      return new JSONObject().put("type", "object")
          .put("properties", new JSONObject().put("path", pathProp(PATH_HELP)))
          .put("required", new JSONArray().put("path"));
    }

    public ToolResult call(JSONObject args) throws ToolException {
      Path file = root.resolve(args.getString("path"));
      String rel = requiredPath(root, file);
      if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
        throw new ToolException("'" + rel + "' does not exist: call list_files to see what exists");
      }
      try {
        if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
          try (DirectoryStream<Path> ds = Files.newDirectoryStream(file)) {
            if (ds.iterator().hasNext()) {
              throw new ToolException("Directory '" + rel + "' is not empty: delete its entries "
                  + "first (list_files shows them)");
            }
          }
        }
        Files.delete(file);
      } catch (IOException e) {
        throw io("delete", rel, e);
      }
      return ToolResult.json(new JSONObject().put("deleted", rel));
    }
  }

  /** Wiki URL of a file: {@code /files/} plus the relative path with each segment URL-encoded. */
  static String url(String rel) {
    StringBuilder out = new StringBuilder("/files");
    for (String segment : rel.split("/")) {
      out.append('/').append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
    }
    return out.toString();
  }
}
