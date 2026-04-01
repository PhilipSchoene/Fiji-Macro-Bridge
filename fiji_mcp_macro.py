import asyncio
import json
import os
import socket
import subprocess
import sys
import time
from typing import Any, Dict, List

from mcp.server import Server
from mcp.types import ImageContent, TextContent, Tool

DEFAULT_HOST = "localhost"
DEFAULT_PORT = 5048
DEFAULT_TIMEOUT = 600
LAUNCH_WAIT_SEC = 30
RETRY_INTERVAL = 2


class FijiMacroServer:
    def __init__(self) -> None:
        self.fiji_host = DEFAULT_HOST
        self.fiji_port = int(os.getenv("FIJI_PORT", DEFAULT_PORT))
        self.fiji_path = os.getenv("FIJI_PATH", "")
        self.timeout = int(os.getenv("FIJI_TIMEOUT", DEFAULT_TIMEOUT))
        self.app = Server("fiji-macro")
        self._setup_handlers()

    def _send_command(self, command: str, args: Dict[str, Any] | None = None) -> Any:
        request = {"command": command, "args": args or {}}
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
                sock.settimeout(self.timeout)
                sock.connect((self.fiji_host, self.fiji_port))
                sock.sendall((json.dumps(request) + "\n").encode("utf-8"))

                response_data = b""
                while True:
                    chunk = sock.recv(4096)
                    if not chunk:
                        break
                    response_data += chunk
                    if b"\n" in chunk:   # newline is always at the very end of the JSON line
                        break

            response = json.loads(response_data.decode("utf-8").strip())
        except ConnectionRefusedError as exc:
            raise ConnectionError("Fiji is not running. Use launch_fiji first.") from exc
        except socket.timeout as exc:
            raise TimeoutError(f"Request to Fiji timed out after {self.timeout}s") from exc
        except (json.JSONDecodeError, ValueError) as exc:
            raise RuntimeError(f"Invalid response from Fiji (malformed JSON): {exc}") from exc

        if response.get("status") == "error":
            raise RuntimeError(response.get("error", "Unknown error from Fiji"))
        return response.get("result")

    def _probe(self) -> bool:
        try:
            result = self._send_command("ping")
            return result == "pong"
        except Exception:
            return False

    def _launch_fiji(self) -> str:
        if self._probe():
            return "Already connected"
        if not self.fiji_path or not os.path.exists(self.fiji_path):
            raise FileNotFoundError("FIJI_PATH is not configured or does not exist.")

        kwargs: Dict[str, Any] = {}
        if sys.platform == "win32":
            kwargs["creationflags"] = (
                subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.DETACHED_PROCESS
            )
        else:
            kwargs["start_new_session"] = True

        subprocess.Popen(
            [self.fiji_path, "-eval", f"run('Fiji Macro Bridge', '{self.fiji_port}');"],
            **kwargs,
        )

        retries = max(1, LAUNCH_WAIT_SEC // RETRY_INTERVAL)
        for _ in range(retries):
            if self._probe():
                return "Fiji launched and bridge connected."
            time.sleep(RETRY_INTERVAL)

        raise TimeoutError(
            "Fiji was launched, but the bridge did not become ready within "
            f"{LAUNCH_WAIT_SEC}s."
        )

    def _setup_handlers(self) -> None:
        @self.app.list_tools()
        async def list_tools() -> List[Tool]:
            return [
                Tool(
                    name="launch_fiji",
                    description="Launch Fiji and start the Fiji Macro Bridge plugin.",
                    inputSchema={"type": "object", "properties": {}},
                ),
                Tool(
                    name="run_macro",
                    description=(
                        "Execute an ImageJ macro (IJ1 macro language). Returns a structured "
                        "response with: result (macro return value), log_lines_added and "
                        "log_total_lines (pass log_total_lines as since_line to get_log to "
                        "retrieve macro output), new_images_opened (list of id+title for any "
                        "images opened during the macro), and results_table_rows (current row "
                        "count of the Results Table)."
                    ),
                    inputSchema={
                        "type": "object",
                        "properties": {
                            "macro": {
                                "type": "string",
                                "description": "ImageJ macro code",
                            }
                        },
                        "required": ["macro"],
                    },
                ),
                Tool(
                    name="get_results",
                    description="Get the shared Fiji Results Table as compact JSON.",
                    inputSchema={"type": "object", "properties": {}},
                ),
                Tool(
                    name="list_open_images",
                    description=(
                        "List all images currently open in Fiji with their ID, title, dimensions, "
                        "type, stack size, and calibration unit. The ID is the stable handle for "
                        "select_image. Call this after any macro that may have opened or closed images."
                    ),
                    inputSchema={"type": "object", "properties": {}},
                ),
                Tool(
                    name="navigate_stack",
                    description=(
                        "Move to a specific channel/slice/frame in the active image stack. "
                        "All arguments are optional — omit any dimension to keep its current "
                        "position. Values are 1-indexed. Returns the new position with total "
                        "counts. Call get_image_content afterward to see the result."
                    ),
                    inputSchema={
                        "type": "object",
                        "properties": {
                            "channel": {
                                "type": "integer",
                                "description": "1-indexed channel (default: current channel)",
                            },
                            "slice": {
                                "type": "integer",
                                "description": "1-indexed Z-slice (default: current slice)",
                            },
                            "frame": {
                                "type": "integer",
                                "description": "1-indexed time frame (default: current frame)",
                            },
                        },
                    },
                ),
                Tool(
                    name="get_log",
                    description=(
                        "Read Fiji's Log window. Pass since_line (the total_lines value from a "
                        "previous call or from a run_macro response) to retrieve only new output "
                        "since that point. This is the primary way to capture output from macros "
                        "that use print() or IJ.log()."
                    ),
                    inputSchema={
                        "type": "object",
                        "properties": {
                            "since_line": {
                                "type": "integer",
                                "description": "Return only lines at or after this 0-based index (default 0)",
                            },
                        },
                    },
                ),
                Tool(
                    name="select_image",
                    description=(
                        "Make an image the active image in Fiji by ID (preferred, always unique) "
                        "or by title (fails clearly if the title is ambiguous). Returns a "
                        "confirmation with the image's properties. Does not take a screenshot — "
                        "call get_image_content separately."
                    ),
                    inputSchema={
                        "type": "object",
                        "properties": {
                            "id": {
                                "type": "integer",
                                "description": "WindowManager image ID (stable, unique handle)",
                            },
                            "title": {
                                "type": "string",
                                "description": "Exact window title (fails if ambiguous)",
                            },
                        },
                    },
                ),
                Tool(
                    name="get_image_content",
                    description=(
                        "Screenshot the active Fiji ImageWindow exactly as it appears on screen "
                        "(preserving LUT/pseudocolor, overlays, ROIs, scale bars) and return it "
                        "as a PNG for visual reasoning, accompanied by full image metadata: "
                        "title, data dimensions, bit depth, image type, channel/slice/frame "
                        "counts, current stack position, display range (min/max brightness), "
                        "spatial calibration (pixel size, Z spacing, time interval, units), "
                        "ROI type and bounds if present, overlay flag, and any embedded "
                        "microscope info property."
                    ),
                    inputSchema={"type": "object", "properties": {}},
                ),
            ]

        @self.app.call_tool()
        async def call_tool(name: str, arguments: dict):
            try:
                if name == "launch_fiji":
                    result = await asyncio.to_thread(self._launch_fiji)
                    return [TextContent(type="text", text=result)]

                if name == "get_image_content":
                    result = await asyncio.to_thread(
                        self._send_command, "get_image_content", arguments
                    )
                    if result is None:
                        raise RuntimeError("Empty response from Fiji for get_image_content")
                    image_b64: str = result["image"]
                    metadata: dict = result["metadata"]
                    # Send metadata as JSON so the LLM can parse every field reliably.
                    meta_text = json.dumps(metadata, indent=2, ensure_ascii=False)
                    return [
                        ImageContent(type="image", data=image_b64, mimeType="image/png"),
                        TextContent(type="text", text=f"Image metadata:\n{meta_text}"),
                    ]

                result = await asyncio.to_thread(self._send_command, name, arguments)
                if name in ("run_macro", "get_results", "list_open_images", "select_image", "get_log", "navigate_stack"):
                    text = json.dumps(result, ensure_ascii=False, separators=(",", ":"))
                else:
                    text = str(result)
                return [TextContent(type="text", text=text)]
            except Exception as exc:
                return [TextContent(type="text", text=f"Error: {exc}")]

    async def run(self) -> None:
        from mcp.server.stdio import stdio_server

        async with stdio_server() as streams:
            await self.app.run(
                streams[0], streams[1], self.app.create_initialization_options()
            )


def main() -> None:
    try:
        asyncio.run(FijiMacroServer().run())
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()

