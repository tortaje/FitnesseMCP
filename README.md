# FitNesse MCP plugin

[![Maven Central](https://img.shields.io/maven-central/v/io.github.tortaje/fitnesse-mcp-plugin)](https://central.sonatype.com/artifact/io.github.tortaje/fitnesse-mcp-plugin)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

A FitNesse plugin that serves a [Model Context Protocol](https://modelcontextprotocol.io) endpoint at
`http://localhost:<FitNesse port + 1>/mcp` (Streamable HTTP, JSON responses), for example
`http://localhost:8081/mcp` when FitNesse runs on port 8080. It lets an MCP client such as Claude Code or
opencode read, edit, search and run FitNesse wiki pages, manage the `files/` directory and inspect Slim
fixtures. The server is stateless (no `Mcp-Session-Id`) and speaks protocol versions `2025-11-25`,
`2025-06-18` and `2025-03-26`.

Requires FitNesse `20260313` and Java 17+.

## Install

```sh
mvn package
mkdir -p <root>/plugins
cp target/fitnesse-mcp-plugin-1.0.0-SNAPSHOT.jar <root>/plugins/
java -jar fitnesse-20260313-standalone.jar -p 8080 -d <root>
```

`<root>` is the directory passed with `-d` (the one that contains `FitNesseRoot`). The jar embeds no third-party
classes; it uses the FitNesse and `org.json` classes already present, and the JDK's built-in HTTP server. On
start-up FitNesse logs `MCP endpoint: http://127.0.0.1:8081/mcp`. Check it with:

```sh
curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' http://localhost:8081/mcp
```

## Configuration

The MCP endpoint has its own HTTP listener, on its own port, next to the FitNesse wiki:

| Property | Default | Meaning |
| --- | --- | --- |
| `MCP_PORT` | FitNesse port + 1 | Port of the MCP listener; `off` disables it. |
| `MCP_HOST` | `127.0.0.1` | Address the listener binds to (localhost only by default; `0.0.0.0` for all interfaces). |

Set them in `plugins.properties` in the FitNesse root directory (the `-d` directory):

```properties
MCP_PORT=9000
MCP_HOST=127.0.0.1
```

or as JVM system properties: `java -DMCP_PORT=9000 -jar fitnesse-20260313-standalone.jar -p 8080 -d <root>`.
An environment variable of the same name works too. If the port is in use, FitNesse still starts and logs a
warning naming the property to change. The listener is not started when FitNesse runs a single command (`-c`).

> **JUnit runner and embedded use.** Every FitNesse context built with the plugin on its classpath starts a
> listener, including the contexts created by FitNesse's JUnit runner (`FitNesseRunner`, `JUnitHelper`) and by
> code that embeds FitNesse. Set `MCP_PORT=off` there (for example `-DMCP_PORT=off` in the test JVM) unless you
> want an MCP endpoint during those runs.

> **Exposing the endpoint.** `MCP_HOST=0.0.0.0` makes the endpoint reachable from the network. With FitNesse's
> default authenticator, which accepts everyone, anyone who can reach the port can then rewrite pages, upload
> files and start test runs, and a test run executes the command in the page's `COMMAND_PATTERN`. Only bind to
> a non-local address together with `-a user:pass` (see [Authentication](#authentication)) on a trusted network.

To try it with your own fixtures, compile them and give a suite page a `!path` to the classes (or jars) and an
`|import|` table naming their package. The Slim test JVM also needs the FitNesse jar on its `!path`. For example:

```
!define TEST_SYSTEM {slim}
!path /path/to/fitnesse-20260313-standalone.jar
!path /path/to/fixtures/classes

|import|
|com.acme.fixtures|
```

`list_fixtures` on any page of that suite then lists the public fixture classes of `com.acme.fixtures`, and
`run_tests` runs its test pages.

## Claude Code

```sh
claude mcp add --transport http fitnesse http://localhost:8081/mcp
```

or in `.mcp.json`:

```json
{"mcpServers": {"fitnesse": {"type": "http", "url": "http://localhost:8081/mcp"}}}
```

## opencode

In `opencode.json`:

```json
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {"fitnesse": {"type": "remote", "url": "http://localhost:8081/mcp", "enabled": true}}
}
```

## Authentication

Every request is checked by the authenticator of the FitNesse instance (the default one accepts everyone;
`-a user:pass` requires that user). When one is configured, send HTTP Basic credentials from the client configuration: a `headers` object with
`"Authorization": "Basic <base64 of user:password>"`, for example

```json
{"mcpServers": {"fitnesse": {"type": "http", "url": "http://localhost:8081/mcp",
  "headers": {"Authorization": "Basic dTpw"}}}}
```

(`dTpw` is `u:p`); missing or wrong credentials get `401`. The `Origin` header, when present, must name
`localhost`, `127.0.0.1` or `[::1]`; other origins get `403`. Only `POST /mcp` is served: other methods get `405`,
other paths `404`, and bodies over 16 MiB `413`.

## Tools

| Tool | Purpose |
| --- | --- |
| `get_page` | Return one page: path, raw wiki text, type, tags, lastModified and child names. |
| `list_pages` | List descendant pages of a page, depth-first, with type and tags. |
| `search_pages` | Case-insensitive search over page names and/or content, with excerpts. |
| `save_page` | Create or update a page (content, type Test/Suite/Static, tags); optional optimistic-lock check. |
| `move_page` | Move and/or rename a page, rewriting references to it. |
| `delete_page` | Delete a page and its children. |
| `list_files` | List a directory of the wiki's `files/` directory. |
| `get_file` | Read a file from `files/` (text, image, or size and URL for other binaries). |
| `upload_file` | Write a text or base64 file into `files/`, creating directories. |
| `delete_file` | Delete a file or empty directory from `files/`. |
| `run_tests` | Start an asynchronous run of a test page or of every test in a suite. |
| `get_run_status` | Progress of a run: state, pages done, counts, elapsed time, failure cause. |
| `get_run_results` | Per-page counts, failures (`assertion`, `exception` or `table`) and HTML output of a run. |
| `cancel_run` | Cancel a queued or running run. |
| `list_fixtures` | List the Slim fixture classes available to a page. |
| `describe_fixture` | Describe a fixture class's constructors and methods with Slim usage hints. |

Typical flow: `run_tests` returns a `runId`; poll `get_run_status` until the state is `completed`, `failed` or
`cancelled`; then call `get_run_results`.

## Limits

- `list_pages`: `depth` defaults to 1, maximum 10.
- `search_pages`: excerpts up to 200 characters, at most 100 results.
- `upload_file`: at most 10 MiB (decoded); `get_file` returns only size and URL for larger files.
- Per-page test output in `get_run_results` is capped at 64 KiB (65536 characters).
- Exception stack traces are cut to 20 lines.
- The 20 most recent completed runs are kept in memory (lost on restart).
- `list_fixtures` returns at most 500 classes.
- Symbolic (linked) pages are skipped in `get_page.children`, `list_pages` and `search_pages`; `files/` access
  is confined to the `files/` directory (symlinks are reported as `link` and not followed).
- A top-level page named `files` cannot be saved or moved into place: FitNesse reserves it for attachments (use
  `upload_file`).

## Known limitations

- Runs started through MCP are not written to FitNesse's test history; runs started from the web UI are
  unaffected.
- One run executes at a time; further runs wait in a queue.
- `get_run_status` reports `failureCause` only for runs in state `failed` (for example when the test JVM cannot
  start).
- Slim errors that appear only in the page output (for example `Bad table!`) are reported by `get_run_results`
  as failures of kind `table`, extracted from that output.

## Development

`mvn verify` runs the unit tests and the end-to-end tests, which build a FitNesse context with the plugin, let it
start its MCP listener on a free port and talk to it with `java.net.http.HttpClient`. Other tests build their
contexts with `MCP_PORT=off`.
