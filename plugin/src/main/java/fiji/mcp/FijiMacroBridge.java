package fiji.mcp;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.macro.Interpreter;
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
                JSONObject request = new JSONObject(line);
                JSONObject response = processCommand(request);
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

    private String runMacro(JSONObject args) throws Exception {
        MacroDialogMonitor monitor = new MacroDialogMonitor();
        monitor.start();
        try {
            Interpreter interpreter = new Interpreter();
            interpreter.setIgnoreErrors(true);
            String result = interpreter.run(args.getString("macro"), null);
            String dialogMessage = monitor.stopAndGetMessage();

            if (dialogMessage != null) {
                throw new Exception(dialogMessage);
            }
            if (interpreter.wasError()) {
                throw new Exception(formatInterpreterError(interpreter));
            }
            if (result == null) {
                return "";
            }
            if ("[aborted]".equals(result)) {
                throw new Exception("Macro aborted");
            }
            return result;
        } finally {
            monitor.stop();
        }
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
            stop();
            if (thread != null) {
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
            if (!dialog.isModal()) {
                return false;
            }
            String title = dialog.getTitle();
            if (title == null) {
                return true;
            }
            String normalizedTitle = title.trim();
            return !normalizedTitle.isEmpty();
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

    private String getImageContent() throws Exception {
        ImagePlus imp = getActiveImage();
        java.awt.image.BufferedImage image;
        if (imp.getRoi() != null || imp.getOverlay() != null) {
            image = imp.flatten().getBufferedImage();
        } else {
            image = imp.getBufferedImage();
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", baos);
        return java.util.Base64.getEncoder().encodeToString(baos.toByteArray());
    }
}

