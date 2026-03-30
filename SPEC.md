# Fiji Macro Bridge v1 Specification

## Overview

Operate Fiji (ImageJ) via its GUI using a minimal toolset from Claude Desktop.

The public interface is limited to four tools: `launch_fiji`, `run_macro`, `get_results`, and `get_image_content`.
The Java side consists of a minimal TCP + JSON bridge, while the Python side serves as the MCP (Model Context Protocol) conversion layer.

## Scope

- Supported operations: ImageJ Macro execution, Results Table retrieval, Active image acquisition (PNG), and Fiji launch assistance.
- `run_js`, ROI-specific tools, window screenshots, and `check_connection` tools are **not** included in v1.
- The Results Table returns the current shared state; it does not isolate results per call.

## Out of Scope / Non-Goals

- Full replication of detailed diagnostic information equivalent to the Debug Window.
- Automatic recovery after a timeout.
- Compatibility with arbitrary/untested Fiji versions.
- Simultaneous control of multiple Fiji instances.
- Coexistence on the same port as the old `fiji-mcp-bridge-1.0.0.jar`.

## Architecture

`Claude Desktop -> MCP -> fiji_mcp_macro.py -> TCP:5048 -> FijiMacroBridge.java -> Fiji GUI`

- `launch_fiji` is the only tool that starts Fiji from Python using `subprocess.Popen`.
- The Java/TCP layer returns images as bare Base64; the MCP layer converts these into `ImageContent`.

## Repository Structure

- `fiji_mcp_macro.py`
- `requirements.txt`
- `README.md`
- `SPEC.md`
- `plugin/pom.xml`
- `plugin/src/main/java/fiji/mcp/FijiMacroBridge.java`
- `plugin/src/main/resources/plugins.config`
- `.gitignore`

## Public Interface

### `launch_fiji`
- **Input:** None
- **Output:** `"Already connected"` or success/failure text regarding the launch.

### `run_macro`
- **Input:** `{ "macro": string }`
- **Success Output:** The return value string of the macro.
- **Failure Output:** Error message string.

### `get_results`
- **Input:** `{}`
- **Output:** Returns a compact JSON array via `TextContent`.

### `get_image_content`
- **Input:** `{}`
- **Output:** `ImageContent(type="image", data=..., mimeType="image/png")`.

---

## Java Plugin Specification

- **Class Name:** `fiji.mcp.FijiMacroBridge`
- **Menu Registration:** `Plugins > Macro Bridge > Fiji Macro Bridge`
- **Dependencies:** `net.imagej:ij:1.54p` (provided), `org.json:json:20231013` (shaded).
- **Target:** Java 8.

### TCP Protocol
- **Request:** `{"command":"...","args":{...}}`
- **Success:** `{"status":"success","result":...}`
- **Error:** `{"status":"error","error":"...","stackTrace":"..."}`
- **Lifecycle:** One request per connection.
- **Encoding:** UTF-8, newline-terminated JSON.
- **Internal Command:** Includes `ping -> "pong"` for Python-side readiness probes.

### `run_macro` Logic
- Creates a new `Interpreter` with `setIgnoreErrors(true)`.
- Executes `interpreter.run(args.getString("macro"), null)` in a worker thread.
- If `interpreter.wasError()` is true, returns an error response using `getErrorMessage()` and `getLineNumber()`.
- Normalizes `null` returns to `""`.
- If `"[aborted]"` is returned, treats it as an error: `{"status":"error","error":"Macro aborted"}`.
- **Modal Dialog Handling:** A monitor thread will automatically close non-interactive modal dialogs that appear during execution, returning the title and body text as an error response.
- Does **not** use EDT wrapping.
- **Timeout:** 10 minutes ($600,000\text{ ms}$).
- On timeout, the worker thread is interrupted, but stopping the macro is not guaranteed.

### `get_image_content` Logic
- Errors if `WindowManager.getCurrentImage()` is `null`.
- Uses `flatten()` if an ROI or overlay exists; otherwise uses `getBufferedImage()`.
- Converts to PNG and returns the Base64 string in the `result` field.

### `get_results` Logic
- Reads `ResultsTable.getResultsTable()` and returns a `JSONArray` of row objects.
- Empty tables return `[]`.
- Skips columns where the heading is `null`, empty, or `"Label"`.
- Uses reflection to access the private `stringColumns` field (required for `ij 1.54p`).
- If reflection fails, returns an error response (no silent fallback).
- Columns in `stringColumns` are treated as JSON strings; `null` becomes `JSONObject.NULL`.
- Other columns use `getValueAsDouble(col, row)`; finite values are numbers, while `NaN`/`Infinity` become `JSONObject.NULL`.
- If `rt.getLabel(row)` is present, adds the `"Label"` key to the row object.

---

## Python MCP Server Specification

- **Dependency:** `mcp>=1.0.0` only.
- No `.env` or `python-dotenv` usage.
- `FIJI_PATH` environment variable is **required**.
- `FIJI_PORT` is optional (default: `5048`).
- `FIJI_TIMEOUT` is optional (default: `600`).
- **Host:** Fixed to `localhost`.

### `launch_fiji` Logic
- Sends a protocol-level probe (ping) to check for an existing bridge.
- If successful, returns `"Already connected"` and does not launch a new process.
- If disconnected, launches Fiji via `[FIJI_PATH, "-eval", f"run('Fiji Macro Bridge', '{self.fiji_port}');"]`.
- On Windows, uses `CREATE_NEW_PROCESS_GROUP | DETACHED_PROCESS`.
- Post-launch, checks readiness with the probe (Timeout: 30s, Interval: 2s).

---

## Claude Desktop Configuration Example

```json
{
  "mcpServers": {
    "fiji-macro": {
      "command": "path/to/fiji-macro-env/bin/python3",
      "args": ["path/to/Fiji_Macro_Bridge/fiji_mcp_macro.py"],
      "env": {
        "FIJI_PATH": "path/to/Fiji/Fiji.app/Contents/MacOS/fiji-macos",
        "FIJI_PORT": "5048"
      }
    }
  }
}
```

## Constraints and Assumptions

- **Target:** Primarily Windows + Claude Desktop for v1.
- **Non-interactive:** Supports non-interactive macros only. Macros using `waitForUser` are not supported.
- **State:** The state of Fiji after a timeout or dialog hang is undefined; a restart may be required.
- **Reflection:** Support for arbitrary string columns depends on `ij 1.54p` internal structures. Overriding version compatibility is out of scope for v1.
