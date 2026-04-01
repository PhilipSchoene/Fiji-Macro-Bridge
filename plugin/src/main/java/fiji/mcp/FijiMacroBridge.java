package fiji.mcp;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.ImageWindow;
import ij.gui.Roi;
import ij.macro.Interpreter;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.plugin.PlugIn;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.Socket;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.EventQueue;
import java.awt.Label;
import java.awt.TextComponent;
import java.awt.Window;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.text.JTextComponent;

public class FijiMacroBridge implements PlugIn {
    private static final int DEFAULT_PORT = 5048;
    private static final int CLIENT_SOCKET_TIMEOUT_MS = 30000;
    private static final long COMMAND_TIMEOUT_MS = 600000L;
    private static volatile ServerSocket serverSocket;
    private static volatile boolean running = false;
    private static volatile Thread serverThread;

    @Override
    public void run(String arg) {
        if (running) {
            stopServer();
            return;
        }

        int port = DEFAULT_PORT;
        String portArg = arg;
        if (portArg == null || portArg.trim().isEmpty()) {
            portArg = System.getenv("FIJI_PORT");
        }
        if (portArg != null && !portArg.trim().isEmpty()) {
            try {
                port = Integer.parseInt(portArg.trim());
            } catch (NumberFormatException e) {
                IJ.log("Invalid port argument: '" + portArg + "', using default " + DEFAULT_PORT);
            }
        }
        startServer(port);
    }

    private void startServer(int port) {
        try {
            serverSocket = new ServerSocket(port, 50, java.net.InetAddress.getLoopbackAddress());
            running = true;
            IJ.log("=== Fiji Macro Bridge ===");
            IJ.log("Started on port " + port);
            IJ.log("Waiting for connections...");

            serverThread = new Thread(() -> {
                while (running) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        handleClient(clientSocket);
                    } catch (IOException e) {
                        if (running) {
                            IJ.log("Connection error: " + e.getMessage());
                        }
                    }
                }
            });
            serverThread.setDaemon(true);
            serverThread.start();
        } catch (IOException e) {
            IJ.error("Fiji Macro Bridge", "Failed to start server: " + e.getMessage());
        }
    }

    private void stopServer() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            IJ.log("Fiji Macro Bridge stopped");
        } catch (IOException e) {
            IJ.log("Error stopping server: " + e.getMessage());
        }
    }

    private void handleClient(Socket clientSocket) {
        try (
                BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), "UTF-8"));
                PrintWriter out = new PrintWriter(new OutputStreamWriter(clientSocket.getOutputStream(), "UTF-8"), true)
        ) {
            clientSocket.setSoTimeout(CLIENT_SOCKET_TIMEOUT_MS);
            String line = in.readLine();
            if (line != null) {
                JSONObject response;
                try {
                    JSONObject request = new JSONObject(line);
                    response = processCommand(request);
                } catch (Exception e) {
                    response = new JSONObject();
                    response.put("status", "error");
                    response.put("error", "Failed to parse request: " + e.getMessage());
                }
                out.println(response.toString());
            }
        } catch (Exception e) {
            IJ.log("Client error: " + e.getMessage());
        } finally {
            try {
                clientSocket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private JSONObject processCommand(JSONObject request) {
        JSONObject response = new JSONObject();
        try {
            String command = request.getString("command");
            JSONObject args = request.optJSONObject("args");
            if (args == null) {
                args = new JSONObject();
            }

            final JSONObject finalArgs = args;
            Object result = runWithTimeout(() -> executeCommand(command, finalArgs), COMMAND_TIMEOUT_MS);
            response.put("status", "success");
            response.put("result", result);
        } catch (Exception e) {
            response.put("status", "error");
            response.put("error", e.getMessage());

            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            response.put("stackTrace", sw.toString());
        }
        return response;
    }

    private Object executeCommand(String command, JSONObject args) throws Exception {
        switch (command) {
            case "ping":
                return "pong";
            case "run_macro":
                return runMacro(args);
            case "get_results":
                return getResults();
            case "get_image_content":
                return getImageContent();
            case "list_open_images":
                return listOpenImages();
            case "select_image":
                return selectImage(args);
            case "get_log":
                return getLog(args);
            case "navigate_stack":
                return navigateStack(args);
            default:
                throw new IllegalArgumentException("Unknown command: " + command);
        }
    }

    private Object runWithTimeout(Callable<Object> task, long timeoutMs) throws Exception {
        final AtomicReference<Object> resultRef = new AtomicReference<Object>(null);
        final AtomicReference<Throwable> errorRef = new AtomicReference<Throwable>(null);

        Thread worker = new Thread(() -> {
            try {
                resultRef.set(task.call());
            } catch (Throwable t) {
                errorRef.set(t);
            }
        });
        worker.setDaemon(true);
        worker.start();
        worker.join(timeoutMs);

        if (worker.isAlive()) {
            worker.interrupt();
            throw new Exception("Execution timed out");
        }
        if (errorRef.get() != null) {
            Throwable error = errorRef.get();
            throw (error instanceof Exception) ? (Exception) error : new Exception(error.getMessage(), error);
        }
        return resultRef.get();
    }

    private ImagePlus getActiveImage() throws Exception {
        ImagePlus imp = WindowManager.getCurrentImage();
        if (imp == null) {
            throw new Exception("No image is currently open");
        }
        return imp;
    }

    private JSONObject runMacro(JSONObject args) throws Exception {
        int logLinesBefore = countLogLines();
        int[] idsBefore = WindowManager.getIDList();
        int[] imageIdsBefore = idsBefore != null ? idsBefore : new int[0];

        MacroDialogMonitor monitor = new MacroDialogMonitor();
        monitor.start();
        String macroResult;
        try {
            Interpreter interpreter = new Interpreter();
            interpreter.setIgnoreErrors(true);
            macroResult = interpreter.run(args.getString("macro"), null);
            String dialogMessage = monitor.stopAndGetMessage();

            if (dialogMessage != null) {
                throw new Exception(dialogMessage);
            }
            if (interpreter.wasError()) {
                throw new Exception(formatInterpreterError(interpreter));
            }
            if ("[aborted]".equals(macroResult)) {
                throw new Exception("Macro aborted");
            }
        } finally {
            monitor.stop();
        }

        if (macroResult == null) {
            macroResult = "";
        }

        int logLinesAfter = countLogLines();
        int[] idsAfter = WindowManager.getIDList();
        int[] imageIdsAfter = idsAfter != null ? idsAfter : new int[0];

        JSONArray newImages = new JSONArray();
        Set<Integer> beforeSet = new HashSet<Integer>();
        for (int id : imageIdsBefore) beforeSet.add(id);
        for (int id : imageIdsAfter) {
            if (!beforeSet.contains(id)) {
                ImagePlus newImp = WindowManager.getImage(id);
                if (newImp != null) {
                    JSONObject entry = new JSONObject();
                    entry.put("id",    id);
                    entry.put("title", newImp.getTitle());
                    newImages.put(entry);
                }
            }
        }

        JSONObject enriched = new JSONObject();
        enriched.put("result",             macroResult);
        enriched.put("log_lines_added",    Math.max(0, logLinesAfter - logLinesBefore));
        enriched.put("log_total_lines",    logLinesAfter);
        enriched.put("new_images_opened",  newImages);
        enriched.put("results_table_rows", getResultsTableRowCount());
        return enriched;
    }

    private int countLogLines() {
        String log = IJ.getLog();
        if (log == null || log.isEmpty()) return 0;
        return log.split("\n").length;
    }

    private int getResultsTableRowCount() {
        ResultsTable rt = ResultsTable.getResultsTable();
        return (rt != null) ? rt.size() : 0;
    }

    private String formatInterpreterError(Interpreter interpreter) {
        String message = interpreter.getErrorMessage();
        int lineNumber = interpreter.getLineNumber();
        if (message == null || message.trim().isEmpty()) {
            message = "Macro execution failed";
        }
        if (lineNumber > 0) {
            return message + " (line " + lineNumber + ")";
        }
        return message;
    }

    private static class MacroDialogMonitor {
        private static final long POLL_INTERVAL_MS = 100L;

        private final AtomicBoolean running = new AtomicBoolean(false);
        private final AtomicReference<String> message = new AtomicReference<String>(null);
        private Thread thread;

        void start() {
            running.set(true);
            thread = new Thread(() -> {
                while (running.get() && message.get() == null) {
                    try {
                        scanAndDismissDialogs();
                        Thread.sleep(POLL_INTERVAL_MS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (Exception ignored) {
                    }
                }
            }, "fiji-macro-dialog-monitor");
            thread.setDaemon(true);
            thread.start();
        }

        void stop() {
            running.set(false);
            if (thread != null) {
                thread.interrupt();
            }
        }

        String stopAndGetMessage() throws InterruptedException {
            running.set(false);
            if (thread != null) {
                thread.interrupt();
                thread.join(500);
            }
            return message.get();
        }

        private void scanAndDismissDialogs() throws Exception {
            final Window[] windows = Window.getWindows();
            for (final Window window : windows) {
                if (!(window instanceof Dialog) || !window.isShowing()) {
                    continue;
                }

                final Dialog dialog = (Dialog) window;
                if (!isBlockingDialog(dialog)) {
                    continue;
                }

                final String text = collectDialogText(dialog);
                if (!message.compareAndSet(null, text)) {
                    return;
                }

                EventQueue.invokeAndWait(new Runnable() {
                    @Override
                    public void run() {
                        dialog.dispose();
                    }
                });
                return;
            }
        }

        private boolean isBlockingDialog(Dialog dialog) {
            return dialog.isModal();
        }

        private String collectDialogText(Dialog dialog) {
            Set<String> lines = new LinkedHashSet<String>();
            addLine(lines, dialog.getTitle());
            collectComponentText(dialog, lines);
            lines.remove("OK");
            lines.remove("Show \"Debug\" Window");
            lines.remove("Cancel");
            lines.remove("Yes");
            lines.remove("No");
            if (lines.isEmpty()) {
                return "Modal dialog interrupted macro execution";
            }
            return joinLines(lines);
        }

        private void collectComponentText(Component component, Set<String> lines) {
            if (component instanceof Label) {
                addLine(lines, ((Label) component).getText());
            } else if (component instanceof TextComponent) {
                addLine(lines, ((TextComponent) component).getText());
            } else if (component instanceof JLabel) {
                addLine(lines, ((JLabel) component).getText());
            } else if (component instanceof JTextComponent) {
                addLine(lines, ((JTextComponent) component).getText());
            } else if (component instanceof AbstractButton) {
                addLine(lines, ((AbstractButton) component).getText());
            }

            if (component instanceof Container) {
                Component[] children = ((Container) component).getComponents();
                for (Component child : children) {
                    collectComponentText(child, lines);
                }
            }
        }

        private void addLine(Set<String> lines, String text) {
            if (text == null) {
                return;
            }
            String normalized = text.replace('\r', '\n').trim();
            if (normalized.isEmpty()) {
                return;
            }
            for (String line : normalized.split("\n+")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    lines.add(trimmed);
                }
            }
        }

        private String joinLines(Set<String> lines) {
            StringBuilder builder = new StringBuilder();
            for (String line : lines) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(line);
            }
            return builder.toString();
        }
    }

    private JSONArray getResults() throws Exception {
        ResultsTable rt = ResultsTable.getResultsTable();
        JSONArray rows = new JSONArray();
        if (rt == null || rt.size() == 0) {
            return rows;
        }

        Hashtable<?, ?> stringColumns = getStringColumns(rt);
        String[] headings = rt.getHeadings();
        for (int row = 0; row < rt.size(); row++) {
            JSONObject rowData = new JSONObject();
            for (String heading : headings) {
                if (heading == null || heading.isEmpty() || "Label".equals(heading)) {
                    continue;
                }

                int col = rt.getColumnIndex(heading);
                if (col == ResultsTable.COLUMN_NOT_FOUND) {
                    continue;
                }

                if (stringColumns.containsKey(Integer.valueOf(col))) {
                    String value = rt.getStringValue(col, row);
                    rowData.put(heading, value != null ? value : JSONObject.NULL);
                } else {
                    double value = rt.getValueAsDouble(col, row);
                    if (Double.isNaN(value) || Double.isInfinite(value)) {
                        rowData.put(heading, JSONObject.NULL);
                    } else {
                        rowData.put(heading, value);
                    }
                }
            }

            String label = rt.getLabel(row);
            if (label != null && !label.isEmpty()) {
                rowData.put("Label", label);
            }
            rows.put(rowData);
        }
        return rows;
    }

    private Hashtable<?, ?> getStringColumns(ResultsTable rt) throws Exception {
        Field field = ResultsTable.class.getDeclaredField("stringColumns");
        field.setAccessible(true);
        Object value = field.get(rt);
        if (value == null) {
            return new Hashtable<Object, Object>();
        }
        if (!(value instanceof Hashtable)) {
            throw new Exception("ResultsTable.stringColumns has unexpected type");
        }

        Hashtable<?, ?> table = (Hashtable<?, ?>) value;
        for (Object entry : table.values()) {
            if (!(entry instanceof ArrayList)) {
                throw new Exception("ResultsTable.stringColumns has unexpected contents");
            }
        }
        return table;
    }

    private JSONObject navigateStack(JSONObject args) throws Exception {
        ImagePlus imp = getActiveImage();

        int nChannels = imp.getNChannels();
        int nSlices   = imp.getNSlices();
        int nFrames   = imp.getNFrames();

        int channel = args.has("channel") ? args.getInt("channel") : imp.getChannel();
        int slice   = args.has("slice")   ? args.getInt("slice")   : imp.getSlice();
        int frame   = args.has("frame")   ? args.getInt("frame")   : imp.getFrame();

        if (channel < 1 || channel > nChannels) {
            throw new Exception("channel " + channel + " out of range [1, " + nChannels + "]");
        }
        if (slice < 1 || slice > nSlices) {
            throw new Exception("slice " + slice + " out of range [1, " + nSlices + "]");
        }
        if (frame < 1 || frame > nFrames) {
            throw new Exception("frame " + frame + " out of range [1, " + nFrames + "]");
        }

        imp.setPosition(channel, slice, frame);
        imp.updateAndDraw();

        JSONObject result = new JSONObject();
        result.put("channel",     channel);
        result.put("slice",       slice);
        result.put("frame",       frame);
        result.put("of_channels", nChannels);
        result.put("of_slices",   nSlices);
        result.put("of_frames",   nFrames);
        return result;
    }

    private JSONObject getLog(JSONObject args) {
        int sinceLine = Math.max(0, args.optInt("since_line", 0));

        String log = IJ.getLog();
        JSONObject result = new JSONObject();

        if (log == null || log.isEmpty()) {
            result.put("total_lines", 0);
            result.put("since_line",  0);
            result.put("lines",       new JSONArray());
            return result;
        }

        String[] allLines = log.split("\n");
        int totalLines = allLines.length;

        JSONArray lines = new JSONArray();
        for (int i = sinceLine; i < totalLines; i++) {
            lines.put(allLines[i]);
        }

        result.put("total_lines", totalLines);
        result.put("since_line",  sinceLine);
        result.put("lines",       lines);
        return result;
    }

    /** Builds the common per-image JSON used by list_open_images and select_image. */
    private JSONObject buildImageEntry(int id, ImagePlus imp) {
        JSONObject entry = new JSONObject();
        entry.put("id",         id);
        entry.put("title",      imp.getTitle());
        entry.put("width",      imp.getWidth());
        entry.put("height",     imp.getHeight());
        entry.put("bit_depth",  imp.getBitDepth());
        entry.put("type",       getImageTypeName(imp.getType()));
        entry.put("channels",   imp.getNChannels());
        entry.put("slices",     imp.getNSlices());
        entry.put("frames",     imp.getNFrames());
        entry.put("pixel_unit", imp.getCalibration().getUnit());
        return entry;
    }

    private JSONArray listOpenImages() {
        JSONArray list = new JSONArray();
        int[] ids = WindowManager.getIDList();
        if (ids == null) {
            return list;
        }
        ImagePlus active = WindowManager.getCurrentImage();
        for (int id : ids) {
            ImagePlus imp = WindowManager.getImage(id);
            if (imp == null) {
                continue;
            }
            JSONObject entry = buildImageEntry(id, imp);
            entry.put("is_active", active != null && imp == active);
            list.put(entry);
        }
        return list;
    }

    private JSONObject selectImage(JSONObject args) throws Exception {
        if (!args.has("id") && !args.has("title")) {
            throw new IllegalArgumentException("Either 'id' or 'title' must be provided");
        }

        final ImagePlus imp;
        final int id;

        if (args.has("id")) {
            id = args.getInt("id");
            imp = WindowManager.getImage(id);
            if (imp == null) {
                throw new Exception("No image with ID " + id);
            }
        } else {
            String title = args.getString("title");
            int[] ids = WindowManager.getIDList();
            ArrayList<Integer> matchIds = new ArrayList<Integer>();
            ImagePlus matchImp = null;
            if (ids != null) {
                for (int candidate : ids) {
                    ImagePlus candidateImp = WindowManager.getImage(candidate);
                    if (candidateImp != null && title.equals(candidateImp.getTitle())) {
                        matchIds.add(candidate);
                        matchImp = candidateImp;
                    }
                }
            }
            if (matchIds.isEmpty()) {
                throw new Exception("No image titled '" + title + "'");
            }
            if (matchIds.size() > 1) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < matchIds.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(matchIds.get(i));
                }
                throw new Exception("Multiple images titled '" + title + "': IDs " + sb);
            }
            id = matchIds.get(0);
            imp = matchImp;
        }

        ImageWindow win = imp.getWindow();
        if (win == null) {
            throw new Exception("Image '" + imp.getTitle() + "' has no display window and cannot be selected.");
        }
        EventQueue.invokeAndWait(() -> win.toFront());

        JSONObject result = buildImageEntry(id, imp);
        result.put("selected", true);
        return result;
    }

    private JSONObject getImageContent() throws Exception {
        ImagePlus imp = getActiveImage();

        // --- Core image properties ---
        JSONObject metadata = new JSONObject();
        metadata.put("title",           imp.getTitle());
        metadata.put("width",           imp.getWidth());
        metadata.put("height",          imp.getHeight());
        metadata.put("bit_depth",       imp.getBitDepth());
        metadata.put("type",            getImageTypeName(imp.getType()));
        metadata.put("channels",        imp.getNChannels());
        metadata.put("slices",          imp.getNSlices());
        metadata.put("frames",          imp.getNFrames());
        metadata.put("is_composite",    imp.isComposite());

        // Current position in the stack — determines what the screenshot shows
        metadata.put("current_channel", imp.getChannel());
        metadata.put("current_slice",   imp.getSlice());
        metadata.put("current_frame",   imp.getFrame());

        // Display range — essential for interpreting 16-bit / 32-bit images
        metadata.put("display_min",     imp.getDisplayRangeMin());
        metadata.put("display_max",     imp.getDisplayRangeMax());

        // --- Spatial and temporal calibration ---
        Calibration cal = imp.getCalibration();
        metadata.put("pixel_width",     cal.pixelWidth);
        metadata.put("pixel_height",    cal.pixelHeight);
        metadata.put("pixel_depth",     cal.pixelDepth);
        metadata.put("pixel_unit",      cal.getUnit());
        metadata.put("frame_interval",  cal.frameInterval);
        metadata.put("time_unit",       cal.getTimeUnit());
        metadata.put("value_unit",      cal.getValueUnit());
        metadata.put("x_origin",        cal.xOrigin);
        metadata.put("y_origin",        cal.yOrigin);
        metadata.put("z_origin",        cal.zOrigin);

        // --- ROI ---
        Roi roi = imp.getRoi();
        if (roi != null) {
            JSONObject roiJson = new JSONObject();
            roiJson.put("type",   roi.getTypeAsString());
            java.awt.Rectangle rb = roi.getBounds();
            roiJson.put("x",      rb.x);
            roiJson.put("y",      rb.y);
            roiJson.put("width",  rb.width);
            roiJson.put("height", rb.height);
            metadata.put("roi", roiJson);
        }

        // --- Overlay and embedded info ---
        metadata.put("has_overlay", imp.getOverlay() != null);

        String info = imp.getInfoProperty();
        if (info != null && !info.trim().isEmpty()) {
            // Truncate to keep token count reasonable while preserving key metadata lines
            metadata.put("info_property",
                info.length() > 1000 ? info.substring(0, 1000) + " ...[truncated]" : info);
        }

        // --- Screenshot: capture the ImageWindow exactly as the user sees it ---
        // Preserves LUT, pseudocolor, overlays, scale bars and ROIs without
        // any custom rendering logic, and produces standard RGB that the LLM handles well.
        ImageWindow win = imp.getWindow();
        if (win == null) {
            throw new Exception(
                "Image '" + imp.getTitle() + "' has no display window. " +
                "Fiji must be running with a GUI for screenshot capture.");
        }

        // Capture on the EDT to avoid tearing during an active repaint
        final AtomicReference<java.awt.image.BufferedImage> screenshotRef = new AtomicReference<>();
        EventQueue.invokeAndWait(() -> {
            java.awt.Rectangle bounds = win.getBounds();
            try {
                java.awt.Robot robot = new java.awt.Robot();
                screenshotRef.set(robot.createScreenCapture(bounds));
            } catch (java.awt.AWTException e) {
                // Robot unavailable (rare: virtual framebuffer without Robot support)
                // Fall back to component rendering
                java.awt.image.BufferedImage fallback = new java.awt.image.BufferedImage(
                        win.getWidth(), win.getHeight(),
                        java.awt.image.BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics g = fallback.getGraphics();
                try {
                    win.paint(g);
                } finally {
                    g.dispose();
                }
                screenshotRef.set(fallback);
            }
        });

        java.awt.image.BufferedImage screenshot = screenshotRef.get();
        metadata.put("screenshot_width",  screenshot.getWidth());
        metadata.put("screenshot_height", screenshot.getHeight());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(screenshot, "png", baos);
        String encoded = java.util.Base64.getEncoder().encodeToString(baos.toByteArray());

        JSONObject result = new JSONObject();
        result.put("image",    encoded);
        result.put("metadata", metadata);
        return result;
    }

    /** Maps the ImagePlus type constant to a human-readable string. */
    private String getImageTypeName(int type) {
        switch (type) {
            case ImagePlus.GRAY8:     return "GRAY8";
            case ImagePlus.GRAY16:    return "GRAY16";
            case ImagePlus.GRAY32:    return "GRAY32";
            case ImagePlus.COLOR_256: return "COLOR_256";
            case ImagePlus.COLOR_RGB: return "COLOR_RGB";
            default:                  return "UNKNOWN(" + type + ")";
        }
    }
}

