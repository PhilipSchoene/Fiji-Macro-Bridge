## Fiji Macro Bridge: MCP Server for Claude Desktop 

This project provides a minimal Model Context Protocol (MCP) server that allows Claude Desktop to control a running Fiji/ImageJ instance on macOS. It enables Claude to launch Fiji, execute macros, and retrieve image data or results tables directly into the chat interface.

### Architecture
The bridge operates using a tiered communication stack: Claude Desktop → Python MCP Server → TCP (Port 5048) → Java Plugin → Fiji GUI.

### Prerequisites
* **macOS**: Any recent version.
* **Fiji**: Installed on your local machine.
* **Java (Maven)**: OpenJDK 11 or newer and Maven installed.
* **Python 3**: A standard installation.
* **Claude Desktop**: Installed and configured.

### Installation Steps

#### 1. Build the Java Plugin
Use Maven to package the Java component into a JAR file. The project targets Java 8 bytecode, ensuring compatibility with Fiji's bundled JVM.

```bash
cd plugin
mvn package
```

On success, the JAR is generated at `plugin/target/fiji-macro-bridge-1.0.0.jar`. The JAR contains shaded `org.json` and `plugins.config` as required by the specification.

#### 2. Install the Plugin in Fiji
Copy the generated JAR file into your Fiji `plugins` directory:

```bash
cp plugin/target/fiji-macro-bridge-1.0.0.jar /path/to/Fiji.app/plugins/
```

#### 3. Setup Python Environment
Create a dedicated virtual environment for the MCP server to keep dependencies isolated:

```bash
python3 -m venv fiji-macro-env
source fiji-macro-env/bin/activate
pip install mcp
```

#### 4. Configure Claude Desktop
Add the bridge to your Claude Desktop configuration file, typically located at `~/Library/Application Support/Claude/claude_desktop_config.json`.

```json
{
  "mcpServers": {
    "fiji-macro": {
      "command": "/path/to/fiji-macro-env/bin/python3",
      "args": ["/path/to/fiji_mcp_macro.py"],
      "env": {
        "FIJI_PATH": "/path/to/Fiji.app/Contents/MacOS/fiji-macos",
        "FIJI_PORT": "5048"
      }
    }
  }
}
```

Restart Claude Desktop completely (**Cmd+Q**) after saving to apply changes.

### Usage
1. **Start Fiji**: Open the application normally.
2. **Activate the Bridge**: In the Fiji menu, navigate to **Plugins > Macro Bridge > Fiji Macro Bridge**.
3. **Verify**: Check the Fiji Log window (**Window > Log**) for the confirmation message: "Started on port 5048".
4. **Test**: Ask Claude: "Run the ImageJ macro getTitle() and tell me what it returns".

### Exposed Tools
* **launch_fiji**: Launch Fiji and start the bridge plugin.
* **run_macro**: Execute an ImageJ macro (IJ1 macro language).
* **get_results**: Read the Fiji Results Table as JSON.
* **get_image_content**: Get the active image as a PNG.

### Limitations
* **Interactive Macros**: Macros using `waitForUser` or other dialogs are not supported.
* **Instance Control**: The bridge controls one Fiji instance at a time on localhost only.
* **Version Specifics**: `get_results` uses reflection on `ResultsTable.stringColumns`, which is tied to ImageJ 1.54p.
