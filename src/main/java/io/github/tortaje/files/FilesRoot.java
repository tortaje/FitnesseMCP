package io.github.tortaje.files;

import io.github.tortaje.protocol.ToolException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Confines file access to one directory (FitNesseRoot/files), including through symlinks. */
public final class FilesRoot {
  private final Path filesDir;

  public FilesRoot(Path filesDir) {
    this.filesDir = filesDir.toAbsolutePath().normalize();
  }

  public Path dir() { return filesDir; }

  /**
   * Resolves a path relative to the files directory. An empty path or "." yields the directory
   * itself. Rejects absolute paths, paths leaving the directory and paths whose existing
   * ancestry (or the path itself) is, or links to, something outside the directory.
   */
  public Path resolve(String relative) throws ToolException {
    if (relative == null) relative = "";
    if (relative.indexOf('\\') >= 0 || relative.startsWith("/")) {
      throw new ToolException("Invalid path '" + relative + "': use a relative path with '/' "
          + "separators, e.g. docs/readme.txt");
    }
    Path resolved;
    try {
      if (Path.of(relative).isAbsolute()) {
        throw new ToolException("Invalid path '" + relative + "': absolute paths are not allowed, "
            + "use a path relative to the files directory");
      }
      resolved = filesDir.resolve(relative).normalize();
    } catch (InvalidPathException e) {
      throw new ToolException("Invalid path '" + relative + "': it contains characters that "
          + "are not allowed in file names");
    }
    if (!resolved.startsWith(filesDir)) {
      throw new ToolException("Path '" + relative + "' is outside the files directory: "
          + "use a path inside it without '..' segments");
    }
    checkNoEscape(resolved, relative);
    return resolved;
  }

  /** The '/'-separated path of {@code p} relative to the files directory ("" for the directory). */
  public String relativize(Path p) {
    return filesDir.relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
  }

  private void checkNoEscape(Path resolved, String relative) throws ToolException {
    try {
      Path realRoot = Files.exists(filesDir) ? filesDir.toRealPath() : null;
      Path current = filesDir;
      for (Path name : filesDir.relativize(resolved)) {
        current = current.resolve(name);
        if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) return; // nothing further exists
        if (realRoot == null) return;
        Path real;
        try {
          real = current.toRealPath();
        } catch (IOException dangling) {
          throw escape(relative); // a broken symlink cannot be shown to stay inside
        }
        if (!real.startsWith(realRoot)) throw escape(relative);
      }
    } catch (IOException e) {
      throw new ToolException("Cannot check path '" + relative + "': " + e.getClass().getSimpleName());
    }
  }

  private static ToolException escape(String relative) {
    return new ToolException("Path '" + relative + "' leads outside the files directory through "
        + "a symbolic link: use a path that stays inside it");
  }
}
