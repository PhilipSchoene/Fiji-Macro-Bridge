# Fiji Macro Bridge — Linux Install Guide

## 1. Install dependencies

```bash
sudo apt update && sudo apt install maven openjdk-11-jdk
curl -LsSf https://astral.sh/uv/install.sh | sh
```

## 2. Build the Java plugin

```bash
cd plugin
mvn package
```

## 3. Install plugin in Fiji

```bash
cp plugin/target/fiji-macro-bridge-1.0.0.jar /path/to/Fiji/plugins/
```

## 4. Start the bridge in Fiji

Plugins → Macro Bridge → Fiji Macro Bridge  
Verify `Started on port 5048` in the Log window.

## 5. Add MCP server to Claude Code

```bash
claude mcp add-json fiji-macro --scope user '{
  "type": "stdio",
  "command": "uv",
  "args": ["run", "--with", "mcp", "/path/to/fiji_mcp_macro.py"],
  "env": {
    "FIJI_PATH": "/path/to/fiji-linux-arm64",
    "FIJI_PORT": "5048"
  }
}'
```

Verify: `claude mcp list` — should show `fiji-macro: ✓ Connected`.

## 6. Add CLAUDE.md

Create `CLAUDE.md` in your working directory:

```markdown
# Fiji Macro Bridge

When working with Fiji/ImageJ, ALWAYS use the fiji-macro MCP tools instead of bash commands.

Available tools: launch_fiji, run_macro, get_image_content, list_open_images, select_image, navigate_stack, get_log, get_results.

Workflow:
1. launch_fiji
2. list_open_images
3. select_image(id=...)
4. get_image_content
5. run_macro(macro=...) for processing
6. get_log / get_results for output

Never shell out to Fiji via command line. Always use the MCP tools.
```

## Done

Launch Claude Code and ask it to interact with Fiji.
