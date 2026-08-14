package nhcm.jvmrtdp.controllerside.tui;

import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.NonBlockingReader;
import org.jline.utils.WCWidth;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Minimal alternate-screen renderer with raw arrow/function-key input. */
public final class TerminalScreen implements AutoCloseable {
    static final String RESET = "\033[0m";
    static final String REVERSE = "\033[7m";
    static final String BOLD = "\033[1m";
    static final String DIM = "\033[2m";
    static final String CYAN = "\033[36m";
    static final String YELLOW = "\033[33m";
    static final String RED = "\033[31m";

    private final Terminal terminal;
    private final Attributes original;
    private final BlockingQueue<InputEvent> inputEvents = new LinkedBlockingQueue<InputEvent>();
    private final Thread inputThread;
    private volatile boolean closed;
    private List<String> lastFrame = Collections.emptyList();
    private int lastFrameWidth = -1;
    private int lastFrameHeight = -1;

    private TerminalScreen(Terminal terminal) {
        this.terminal = terminal;
        this.original = terminal.getAttributes();
        terminal.enterRawMode();
        terminal.writer().print("\033[?1049h\033[?25l\033[2J\033[H");
        terminal.flush();
        inputThread = new Thread(new Runnable() {
            @Override public void run() { pumpInput(); }
        }, "jvmrtdp-tui-input");
        inputThread.setDaemon(true);
        inputThread.start();
    }

    public static TerminalScreen open() throws IOException {
        Terminal terminal;
        Throwable nativeFailure;
        try {
            terminal = TerminalBuilder.builder().name("JVMRTDP-TUI").system(true)
                    .encoding(StandardCharsets.UTF_8).provider("jni").build();
        } catch (Throwable failure) {
            nativeFailure = failure;
            try {
                terminal = TerminalBuilder.builder().name("JVMRTDP-TUI").system(true)
                        .encoding(StandardCharsets.UTF_8).build();
            } catch (Throwable fallbackFailure) {
                IOException unavailable = new IOException(
                        "Unable to create a full-screen terminal: " + rootMessage(fallbackFailure),
                        fallbackFailure);
                unavailable.addSuppressed(nativeFailure);
                throw unavailable;
            }
        }
        try {
            return new TerminalScreen(terminal);
        } catch (Throwable initializationFailure) {
            try { terminal.close(); } catch (Throwable ignored) { }
            if (initializationFailure instanceof IOException) {
                throw (IOException) initializationFailure;
            }
            throw new IOException("Unable to initialize the full-screen terminal: "
                    + rootMessage(initializationFailure), initializationFailure);
        }
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }

    int width() {
        int value = terminal.getWidth();
        return value > 0 ? value : 80;
    }

    int height() {
        int value = terminal.getHeight();
        return value > 0 ? value : 24;
    }

    int readKey() throws IOException {
        return readKey(0L);
    }

    /**
     * Reads decoded keys from a daemon input pump. Some Windows/JLine providers do not
     * reliably honour timed native reads, which used to prevent completed background
     * operations from being painted until the user pressed another key.
     */
    int readKey(long timeoutMillis) throws IOException {
        final InputEvent event;
        try {
            event = timeoutMillis <= 0L ? inputEvents.take()
                    : inputEvents.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return TuiKey.EOF;
        }
        if (event == null) return TuiKey.NONE;
        if (event.failure != null) throw event.failure;
        return event.key;
    }

    private void pumpInput() {
        while (!closed) {
            try {
                int key = decodeKey();
                inputEvents.offer(InputEvent.key(key));
                if (key == TuiKey.EOF) return;
            } catch (IOException failure) {
                if (!closed) inputEvents.offer(InputEvent.failure(failure));
                return;
            } catch (Throwable failure) {
                if (!closed) inputEvents.offer(InputEvent.failure(new IOException(
                        "Terminal input failed: " + rootMessage(failure), failure)));
                return;
            }
        }
    }

    private int decodeKey() throws IOException {
        int first = terminal.reader().read();
        if (first == TuiKey.CTRL_C || first == TuiKey.CTRL_G) return TuiKey.ESCAPE;
        if (first == '\r' || first == '\n') return TuiKey.ENTER;
        if (first == 127 || first == 8) return TuiKey.BACKSPACE;
        if (first != 27) return first;
        int second = terminal.reader().read(80L);
        if (second == NonBlockingReader.READ_EXPIRED || second < 0) return TuiKey.ESCAPE;
        if (second == 27) return TuiKey.ESCAPE;
        if (second != '[' && second != 'O') return TuiKey.ESCAPE;
        StringBuilder sequence = new StringBuilder();
        while (sequence.length() < 8) {
            int value = terminal.reader().read(80L);
            if (value == NonBlockingReader.READ_EXPIRED || value < 0) break;
            sequence.append((char) value);
            if (Character.isLetter(value) || value == '~') break;
        }
        String value = sequence.toString();
        if ("A".equals(value)) return TuiKey.UP;
        if ("B".equals(value)) return TuiKey.DOWN;
        if ("C".equals(value)) return TuiKey.RIGHT;
        if ("D".equals(value)) return TuiKey.LEFT;
        if ("1;5C".equals(value)) return TuiKey.CTRL_RIGHT;
        if ("1;5D".equals(value)) return TuiKey.CTRL_LEFT;
        if ("H".equals(value) || "1~".equals(value) || "7~".equals(value)) return TuiKey.HOME;
        if ("F".equals(value) || "4~".equals(value) || "8~".equals(value)) return TuiKey.END;
        if ("5~".equals(value)) return TuiKey.PAGE_UP;
        if ("6~".equals(value)) return TuiKey.PAGE_DOWN;
        if ("3~".equals(value)) return TuiKey.DELETE;
        if ("Z".equals(value)) return TuiKey.SHIFT_TAB;
        if ("12~".equals(value) || "Q".equals(value)) return TuiKey.F2;
        if ("13~".equals(value) || "R".equals(value)) return TuiKey.F3;
        if ("13;2~".equals(value)) return TuiKey.SHIFT_F3;
        if ("14~".equals(value) || "S".equals(value)) return TuiKey.F4;
        if ("15~".equals(value)) return TuiKey.F5;
        if ("17~".equals(value)) return TuiKey.F6;
        if ("18~".equals(value)) return TuiKey.F7;
        if ("18;2~".equals(value)) return TuiKey.SHIFT_F7;
        if ("19~".equals(value)) return TuiKey.F8;
        if ("20~".equals(value)) return TuiKey.F9;
        if ("20;2~".equals(value)) return TuiKey.SHIFT_F9;
        if ("21~".equals(value)) return TuiKey.F10;
        return TuiKey.ESCAPE;
    }

    private static final class InputEvent {
        private final int key;
        private final IOException failure;

        private InputEvent(int key, IOException failure) {
            this.key = key;
            this.failure = failure;
        }

        private static InputEvent key(int key) { return new InputEvent(key, null); }
        private static InputEvent failure(IOException failure) {
            return new InputEvent(TuiKey.EOF, failure);
        }
    }

    void draw(List<String> lines) {
        PrintWriter output = terminal.writer();
        int width = Math.max(1, width() - 1);
        int height = height();
        if (width == lastFrameWidth && height == lastFrameHeight && lines.equals(lastFrame)) return;
        lastFrame = new ArrayList<String>(lines);
        lastFrameWidth = width;
        lastFrameHeight = height;
        output.print("\033[?25l\033[H");
        for (int row = 0; row < height; row++) {
            String line = row < lines.size() ? lines.get(row) : "";
            output.print(cropAnsi(line, width));
            output.print(RESET + "\033[K");
            if (row + 1 < height) output.print("\r\n");
        }
        terminal.flush();
    }

    static String crop(String value, int width) {
        if (width <= 0) return "";
        String normalized = TuiViewport.expandTabs(value == null ? "" : value);
        if (displayWidth(normalized) <= width) return normalized;
        return plainPrefix(normalized, Math.max(0, width - 1)) + ">";
    }

    static String pad(String value, int width) {
        String cropped = crop(value, width);
        StringBuilder result = new StringBuilder(cropped);
        int columns = displayWidth(cropped);
        while (columns++ < width) result.append(' ');
        return result.toString();
    }

    static String cropAnsi(String value, int width) {
        if (width <= 0) return RESET;
        boolean truncated = displayWidth(value) > width;
        int contentLimit = truncated ? Math.max(0, width - 1) : width;
        StringBuilder result = new StringBuilder();
        int visible = 0;
        for (int index = 0; index < value.length();) {
            char character = value.charAt(index);
            if (character == '\033') {
                int end = sgrEnd(value, index);
                if (end > index) {
                    result.append(value, index, end + 1);
                    index = end + 1;
                    continue;
                }
                String escaped = "\\e";
                int take = Math.min(escaped.length(), contentLimit - visible);
                if (take <= 0) break;
                result.append(escaped, 0, take);
                visible += take;
                index++;
                continue;
            }
            String escaped = escapedControl(character);
            if (escaped != null) {
                int take = Math.min(escaped.length(), contentLimit - visible);
                if (take <= 0) break;
                result.append(escaped, 0, take);
                visible += take;
                index++;
                continue;
            }
            int codePoint = value.codePointAt(index);
            int codePointWidth = cellWidth(codePoint);
            if (visible + codePointWidth > contentLimit) break;
            result.appendCodePoint(codePoint);
            visible += codePointWidth;
            index += Character.charCount(codePoint);
        }
        if (truncated) result.append('>');
        result.append(RESET);
        return result.toString();
    }

    static int displayWidth(String value) {
        int visible = 0;
        for (int index = 0; index < value.length();) {
            if (value.charAt(index) == '\033') {
                int end = sgrEnd(value, index);
                if (end > index) { index = end + 1; continue; }
                visible += 2;
                index++;
                continue;
            }
            String escaped = escapedControl(value.charAt(index));
            if (escaped != null) {
                visible += escaped.length();
                index++;
                continue;
            }
            int codePoint = value.codePointAt(index);
            visible += cellWidth(codePoint);
            index += Character.charCount(codePoint);
        }
        return visible;
    }

    private static String plainPrefix(String value, int maximumWidth) {
        StringBuilder result = new StringBuilder();
        int width = 0;
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            int codePointWidth = cellWidth(codePoint);
            if (width + codePointWidth > maximumWidth) break;
            result.appendCodePoint(codePoint);
            width += codePointWidth;
            index += Character.charCount(codePoint);
        }
        return result.toString();
    }

    private static int cellWidth(int codePoint) {
        return Math.max(0, WCWidth.wcwidth(codePoint));
    }

    /** Accept only SGR attributes. Cursor movement, erase and OSC sequences become visible text. */
    private static int sgrEnd(String value, int index) {
        if (index + 2 >= value.length() || value.charAt(index + 1) != '[') return -1;
        int cursor = index + 2;
        while (cursor < value.length()) {
            char character = value.charAt(cursor);
            if (character == 'm') return cursor;
            if ((character < '0' || character > '9') && character != ';') return -1;
            if (cursor - index > 16) return -1;
            cursor++;
        }
        return -1;
    }

    private static String escapedControl(char character) {
        if (character == '\r') return "\\r";
        if (character == '\n') return "\\n";
        if (character == '\t') return "    ";
        if (character < 0x20 || (character >= 0x7f && character <= 0x9f)
                || character == '\u2028' || character == '\u2029') {
            return String.format(java.util.Locale.ROOT, "\\u%04x", (int) character);
        }
        return null;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        inputThread.interrupt();
        try {
            terminal.setAttributes(original);
            terminal.writer().print(RESET + "\033[?25h\033[?1049l");
            terminal.flush();
        } catch (Throwable ignored) {
            // Closing the UI must never turn a successful detach/exit into a stack trace.
        } finally {
            try { terminal.close(); } catch (Throwable ignored) { }
        }
    }
}
