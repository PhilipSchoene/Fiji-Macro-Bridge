## Fiji Macro Bridge: MCP Server for Claude Desktop

This project provides a Model Context Protocol (MCP) server that allows Claude Desktop
to control a running Fiji/ImageJ instance on macOS. Claude can launch Fiji, execute
macros, inspect and navigate images, read the log and results table, and perform expert
bioimage analysis — all directly from the chat interface.

### Architecture

```
Claude Desktop → Python MCP Server → TCP (port 5048, loopback) → Java Plugin → Fiji GUI
```

The Python server (`fiji_mcp_macro.py`) handles the MCP protocol and forwards commands
as JSON over a loopback TCP socket to the Java plugin (`FijiMacroBridge.java`) running
inside Fiji. One connection is opened per call; the Java side closes it after replying.

### Prerequisites

- **macOS**: Any recent version.
- **Fiji**: Installed on your local machine.
- **Java / Maven**: OpenJDK 11 or newer and Maven.
- **Python 3.10+**: Standard installation.
- **uv**: Fast Python package manager (see Installation below).
- **Claude Desktop**: Installed and configured.

### Installation

#### 1. Build the Java plugin

```bash
cd plugin
mvn package
```

The JAR is generated at `plugin/target/fiji-macro-bridge-1.0.0.jar` (shaded with
`org.json` and the required `plugins.config`).

#### 2. Install the plugin in Fiji

```bash
cp plugin/target/fiji-macro-bridge-1.0.0.jar /path/to/Fiji.app/plugins/
```

#### 3. Install uv

```bash
brew install uv
```

No virtual environment setup or `pip install` is needed — `uv` manages dependencies
automatically when launching the server.

#### 4. Configure Claude Desktop

Edit `~/Library/Application Support/Claude/claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "fiji-macro": {
      "command": "uv",
      "args": [
        "run",
        "--with", "mcp",
        "/path/to/fiji_mcp_macro.py"
      ],
      "env": {
        "FIJI_PATH": "/path/to/Fiji.app/Contents/MacOS/fiji-macos",
        "FIJI_PORT": "5048"
      }
    }
  }
}
```

> **Note:** If Claude Desktop cannot find `uv`, replace `"uv"` with its full path.
> Run `which uv` in a terminal to get it (e.g. `/opt/homebrew/bin/uv`).

Claude Desktop must be fully quit before this change takes effect — closing the window
is not enough. Quit the application entirely via the menu bar, then relaunch it. Claude
loads MCP server configurations only on startup.

#### 5. Apply the system prompt (optional but recommended)

Open `SKILL.md` and paste its contents as a system prompt in your Claude Desktop
conversation. This gives Claude expert bioimage analysis guidance and the correct
tool workflow for this bridge.

### Usage

1. **Start Fiji** — open the application normally.
2. **Activate the bridge** — in Fiji: **Plugins → Macro Bridge → Fiji Macro Bridge**.
3. **Verify** — check the Fiji Log window (**Window → Log**) for: `Started on port 5048`.
4. **Use Claude** — the bridge is ready. If Fiji is not yet running, ask Claude to call
   `launch_fiji` first.

### Tools

| Tool | Description |
|------|-------------|
| `launch_fiji` | Launch Fiji and start the bridge. Idempotent — safe to call at the start of every session. |
| `run_macro` | Execute an IJ1 macro. Returns the macro result, log delta (`log_lines_added`, `log_total_lines`), any newly opened images (`new_images_opened`), and the current Results Table row count. |
| `get_image_content` | EDT screenshot of the active image window as PNG, plus full metadata: dimensions, bit depth, type, stack position, display range, spatial calibration, ROI, overlay flag, and info property. |
| `get_results` | Retrieve the Fiji Results Table as a compact JSON array. |
| `list_open_images` | List all open images with their stable ID, title, dimensions, type, stack size, calibration unit, and active flag. |
| `select_image` | Make an image active by ID (preferred, always unique) or by title (fails clearly if ambiguous). |
| `navigate_stack` | Move to a specific channel / Z-slice / time frame (1-indexed, bounds-validated). |
| `get_log` | Read the Fiji Log window. Pass `since_line` (from a previous `get_log` or `run_macro` response) to retrieve only new output. |

### Recommended workflow

```
launch_fiji
  → list_open_images          # orient: what is open?
  → select_image(id=...)      # select by stable ID
  → get_image_content         # visual + metadata snapshot
  → navigate_stack(slice=N)   # move through Z/time/channel
  → get_image_content         # confirm new view
  → run_macro(macro=...)      # process or measure
  → get_log(since_line=...)   # retrieve macro output
  → get_results               # retrieve measurements
```

### Limitations

- **Interactive macros**: macros using `waitForUser`, `Dialog.create()`, or other
  blocking UI calls are not supported. The bridge detects and dismisses modal dialogs,
  surfacing their message as an error.
- **Single instance**: the bridge controls one Fiji instance at a time on localhost only.
- **GUI required**: `get_image_content` uses `java.awt.Robot` to capture the image
  window; Fiji must be running with a display.
- **Reflection dependency**: `get_results` accesses `ResultsTable.stringColumns` via
  reflection, which is tied to ImageJ 1.54p. This may break on future ImageJ versions.
