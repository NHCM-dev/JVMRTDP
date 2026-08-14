package nhcm.jvmrtdp.controllerside.tui;

import nhcm.jvmrtdp.api.jvmti.JvmDebuggerState;
import nhcm.jvmrtdp.api.jvmti.JvmDebuggerLocal;
import nhcm.jvmrtdp.api.jvmti.JvmBreakpointInfo;
import nhcm.jvmrtdp.api.jvmti.JvmBreakpointCondition;
import nhcm.jvmrtdp.api.jvmti.JvmFieldWatchInfo;
import nhcm.jvmrtdp.api.jvmti.JvmStackFrame;
import nhcm.jvmrtdp.api.jvmti.JvmEventBreakpointInfo;
import nhcm.jvmrtdp.api.jvmti.JvmEventBreakpointSpec;
import nhcm.jvmrtdp.api.bytecode.JvmBytecodePatch;
import nhcm.jvmrtdp.api.bytecode.JvmBytecodePatchResult;
import nhcm.jvmrtdp.api.hook.JvmStringHookInfo;
import nhcm.jvmrtdp.api.hook.JvmStringHookKind;
import nhcm.jvmrtdp.api.hook.JvmStringAllocationSpec;
import nhcm.jvmrtdp.controllerside.StringAllocationSpecParser;
import nhcm.jvmrtdp.api.reference.JvmReferenceInfo;
import nhcm.jvmrtdp.api.reference.JvmReferenceStrength;
import nhcm.jvmrtdp.controllerside.TargetSession;
import nhcm.jvmrtdp.controllerside.RemoteArgumentList;
import nhcm.jvmrtdp.command.CommandLine;
import nhcm.jvmrtdp.controllerside.debug.DebuggerFreezeReport;
import nhcm.jvmrtdp.controllerside.analysis.BytecodeInstruction;
import nhcm.jvmrtdp.controllerside.analysis.ClassFileMethod;
import nhcm.jvmrtdp.controllerside.analysis.ClassFileView;
import nhcm.jvmrtdp.controllerside.analysis.ClassDecompiler;
import nhcm.jvmrtdp.controllerside.analysis.JvmClassFileParser;
import nhcm.jvmrtdp.controllerside.analysis.JvmClassPathCatalog;
import nhcm.jvmrtdp.controllerside.analysis.DecompilerEngine;
import nhcm.jvmrtdp.controllerside.analysis.DecompilationResult;
import nhcm.jvmrtdp.handles.java.RemoteClass;
import nhcm.jvmrtdp.handles.java.RemoteClassInfo;
import nhcm.jvmrtdp.handles.java.RemoteField;
import nhcm.jvmrtdp.handles.java.RemoteMapEntry;
import nhcm.jvmrtdp.handles.java.RemoteMethod;
import nhcm.jvmrtdp.handles.java.RemoteObject;
import nhcm.jvmrtdp.handles.java.RemoteObjectDebugInfo;
import nhcm.jvmrtdp.handles.java.RemotePackage;
import nhcm.jvmrtdp.handles.jvm.RemoteJvmtiThread;
import nhcm.jvmrtdp.handles.search.RemoteClassQuery;
import nhcm.jvmrtdp.handles.search.RemoteMemberQuery;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** Context-oriented package browser and bytecode debugger for one attached JVM. */
public final class TargetTui implements AutoCloseable {
    private enum Tab {
        BROWSE, CONTEXT, REFERENCES, FIELDS, METHODS, SOURCE, BYTECODE, DEBUG,
        STRINGS, FRAMES, LOCALS, BREAKPOINTS, THREADS
    }

    private final TargetSession session;
    private TerminalScreen screen;
    private final TuiTaskRunner tasks = new TuiTaskRunner("JVMRTDP-TUI-worker");
    private final int[] selections = new int[Tab.values().length];
    private final int[] scrolls = new int[Tab.values().length];
    private final int[] horizontalOffsets = new int[Tab.values().length];
    private final List<TuiBrowserEntry> browserEntries = new ArrayList<TuiBrowserEntry>();
    private final List<TuiBrowserEntry> visibleBrowserEntries = new ArrayList<TuiBrowserEntry>();
    private final List<RemoteField> fields = new ArrayList<RemoteField>();
    private final List<RemoteMethod> methods = new ArrayList<RemoteMethod>();
    private final List<String> contextLines = new ArrayList<String>();
    private final List<String> sourceLines = new ArrayList<String>();
    private final List<String> debuggerStack = new ArrayList<String>();
    private final List<JvmStackFrame> debuggerFrames = new ArrayList<JvmStackFrame>();
    private final List<String> constantPool = new ArrayList<String>();
    private final List<String> debugSearchResults = new ArrayList<String>();
    private final List<JvmDebuggerState> debuggerStates = new ArrayList<JvmDebuggerState>();
    private final List<JvmDebuggerLocal> debuggerLocals = new ArrayList<JvmDebuggerLocal>();
    private final List<RemoteJvmtiThread> debuggerThreads = new ArrayList<RemoteJvmtiThread>();
    private final List<String> lastDebuggerStack = new ArrayList<String>();
    private final List<String> lastDebuggerLocals = new ArrayList<String>();
    private final Map<String, BreakpointSpec> breakpoints = new LinkedHashMap<String, BreakpointSpec>();
    private final Map<String, JvmFieldWatchInfo> fieldWatches =
            new LinkedHashMap<String, JvmFieldWatchInfo>();
    private final Deque<String> errors = new ArrayDeque<String>();

    private Tab tab = Tab.BROWSE;
    private String packageName = "";
    private String browserTitle = "package:<root>";
    private String browserFilter = "";
    private String memberFilter = "";
    private String lastSearch = "";
    private String listSearch = "";
    private String viewSearch = "";
    private boolean searchMode;
    private boolean showRuntime;
    private boolean showArrays;
    /** Unloaded class files are browsed in a separate catalog, never mixed with live classes. */
    private boolean browseUnloaded;
    private String unloadedMemberOwner = "";
    private JvmClassPathCatalog classPathCatalog;
    /** Offline class-file context. It mirrors a live CLASS context without loading the class. */
    private JvmClassPathCatalog.ClassEntry unloadedContextClass;
    private final List<JvmClassPathCatalog.Member> unloadedFields =
            new ArrayList<JvmClassPathCatalog.Member>();
    private final List<JvmClassPathCatalog.Member> unloadedMethods =
            new ArrayList<JvmClassPathCatalog.Member>();
    private boolean showSpecialMethods;
    /** Unoverridden java.lang.Object methods are noise in most application classes. */
    private boolean hideInheritedObjectMethods = true;
    /** Static members are visible by default and can be hidden independently with @. */
    private boolean showStaticMembers = true;
    /** Instance fields and virtual methods are visible by default; # toggles them independently. */
    private boolean showVirtualMembers = true;
    private TuiBrowserEntry pendingMemberResult;
    private RemoteClass contextClass;
    private boolean classContext;
    private RemoteMethod selectedMethod;
    private DecompilerEngine engine = DecompilerEngine.CFR;
    private String sourceTitle = "Select a context, then press A to decompile a class or S to decompile a method.";
    private String sourceClass = "";
    private String sourceMethod = "";
    private String sourceDescriptor = "";
    private JvmClassPathCatalog.ClassEntry sourceCatalogClass;
    private final NavigableMap<Integer, Integer> sourceBciToLine = new TreeMap<Integer, Integer>();
    private ClassFileMethod bytecode;
    private String bytecodeClass = "";
    private String bytecodeMethod = "";
    private String bytecodeDescriptor = "";
    private JvmClassPathCatalog.ClassEntry bytecodeCatalogClass;
    private int pendingBytecodeLocation = -1;
    private int pendingSourceBci = -1;
    private JvmDebuggerState debuggerState;
    private int debuggerFrameDepth;
    private DebuggerFreezeReport lastFreezeReport;
    private long activeDebuggerSequence = -1L;
    private long lastObservedStopSequence = -1L;
    private String debuggerLocalsError = "";
    private String lastStopSummary = "";
    private long lastDebuggerPollAt;
    private long lastDebuggerFullRefreshAt;
    private long lastLiveSampleAt;
    private String followedThreadName = "";
    private boolean liveFollowEnabled = true;
    private boolean liveSampleAvailable;
    private long liveSampleCapturedAt;
    private String liveSampleActual = "";
    private String liveSampleView = "";
    private String liveSampleError = "";
    private int liveFollowFrameDepth = -1;
    private Boolean inspectorVisibleOverride;
    private boolean inspectorFocused;
    private int inspectorScroll;
    private int inspectorHorizontal;
    private int statusPage;
    private String pagedStatus = "";
    private String status = "Opening root package...";
    private long synchronizedContextRevision = -1L;
    private long synchronizedBytecodeRevision = -1L;
    private long scheduledContextRevision = -1L;
    private boolean browserInitialized;
    private boolean closed;

    public TargetTui(TargetSession session) {
        this.session = session;
        this.synchronizedBytecodeRevision = session.instrumentation().bytecode().revision();
        synchronizeManagedControls();
    }

    /** Compatibility constructor for callers that run one TUI screen directly. */
    public TargetTui(TargetSession session, TerminalScreen screen) {
        this(session);
        this.screen = screen;
    }

    public TuiResult run() throws IOException {
        if (screen == null) throw new IllegalStateException("No terminal screen is configured");
        return run(screen);
    }

    /** Runs this session-scoped TUI on a newly opened terminal screen. */
    public TuiResult run(TerminalScreen activeScreen) throws IOException {
        if (closed) throw new IllegalStateException("TUI is closed");
        screen = activeScreen;
        synchronizeSessionState();
        try {
            while (session.server().isOpen() && !Thread.currentThread().isInterrupted()) {
                tasks.poll();
                // A refresh request can be temporarily rejected when a background poll and
                // one queued action already occupy the runner. Reconcile every idle frame so
                // CLI -> TUI context changes can never leave empty derived member panes.
                maybeSynchronizeContext();
                maybeSynchronizeBytecode();
                maybeAutoRefreshDebugger();
                render();
                int key = screen.readKey(90L);
                if (key == TuiKey.NONE) continue;
                if (key == TuiKey.EOF) {
                    releasePausedDebugger();
                    return TuiResult.BACK;
                }
                try {
                    TuiResult result = handleKey(key);
                    if (result != null) return result;
                } catch (RuntimeException failure) {
                    recordError(failure);
                }
            }
            return TuiResult.BACK;
        } finally {
            screen = null;
        }
    }

    private void synchronizeSessionState() {
        synchronizeManagedControls();
        if (session.context().revision() != synchronizedContextRevision) {
            memberFilter = "";
            listSearch = "";
            if (session.context().isSet()) requestContextRefresh();
            else {
                clearContextView();
                synchronizedContextRevision = session.context().revision();
            }
        }
        if (!browserInitialized && !session.context().isSet()) requestPackage("");
        requestDebuggerRefresh();
    }

    private void maybeSynchronizeContext() {
        long revision = session.context().revision();
        if (revision == synchronizedContextRevision || tasks.busy()) return;
        memberFilter = "";
        listSearch = "";
        if (session.context().isSet()) requestContextRefresh();
        else {
            clearContextView();
            synchronizedContextRevision = revision;
        }
    }

    private void maybeSynchronizeBytecode() {
        long revision = session.instrumentation().bytecode().revision();
        if (revision == synchronizedBytecodeRevision || tasks.busy()) return;
        synchronizedBytecodeRevision = revision;
        if ((tab == Tab.BYTECODE || tab == Tab.DEBUG) && !bytecodeClass.isEmpty()
                && !bytecodeMethod.isEmpty()) {
            int location = bytecode == null || bytecode.instructions().isEmpty() ? -1
                    : bytecode.instructions().get(bytecodeCursor()).offset();
            if (location >= 0) pendingBytecodeLocation = location;
            requestBytecode(bytecodeClass, bytecodeMethod, bytecodeDescriptor, tab);
            status = "Bytecode transaction changed outside this view; refreshing staged/live code...";
        }
    }

    private void synchronizeManagedControls() {
        Map<String, BreakpointSpec> currentBreakpoints =
                new LinkedHashMap<String, BreakpointSpec>(breakpoints);
        breakpoints.clear();
        for (JvmBreakpointInfo breakpoint : session.jvmti().managedBreakpoints()) {
            String id = breakpoint.id();
            BreakpointSpec existing = currentBreakpoints.get(id);
            breakpoints.put(id, existing != null ? existing
                    : new BreakpointSpec(breakpoint.className(), breakpoint.methodName(),
                            breakpoint.descriptor(), breakpoint.location(), -1,
                            breakpoint.registrationId(), breakpoint.receiverId(),
                            breakpoint.conditionSummary()));
        }
        fieldWatches.clear();
        for (JvmFieldWatchInfo watch : session.jvmti().managedFieldWatches()) {
            fieldWatches.put(watch.id(), watch);
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        tasks.close();
        closeDebuggerStates();
    }

    private TuiResult handleKey(int key) throws IOException {
        if (key == TuiKey.F2 || key == 'c' || key == 'C') {
            if (tasks.userOperationBusy()) { status = "Operation is still running; switch to CLI when it finishes."; return null; }
            return TuiResult.CLI;
        }
        if (key == 'q' || key == 'Q' || key == TuiKey.F10) {
            if (tasks.busy()) { status = "Operation is still running; wait before detaching."; return null; }
            releasePausedDebugger();
            return TuiResult.BACK;
        }
        if (handleInspectorNavigation(key)) return null;
        if (key == TuiKey.DELETE && tab == Tab.REFERENCES) releaseSelectedReference();
        else if (key == TuiKey.DELETE && tab == Tab.STRINGS) removeSelectedStringHook();
        else if ((key == 's' || key == 'S') && tab == Tab.REFERENCES) {
            saveCurrentReference(key == 'S');
        }
        else if (key == '&' && tab == Tab.FIELDS) trackSelectedFieldReference();
        else if (key == ';' && tab == Tab.FIELDS) hookSelectedStringField();
        else if (key == '&' && tab == Tab.STRINGS) trackSelectedStringHookValue();
        else if ((key == 'a' || key == 'A') && tab == Tab.STRINGS) addStringHook();
        else if ((key == 'x' || key == 'X') && tab == Tab.REFERENCES) nullSelectedReference();
        else if (key == TuiKey.F9 && tab == Tab.STRINGS) toggleSelectedStringHook();
        else if (key == TuiKey.CTRL_LEFT) changeTab(-1);
        else if (key == TuiKey.CTRL_RIGHT) changeTab(1);
        else if (key == TuiKey.LEFT && horizontallyScrollable()) moveHorizontal(-8);
        else if (key == TuiKey.RIGHT && horizontallyScrollable()) moveHorizontal(8);
        else if (key == TuiKey.LEFT) changeTab(-1);
        else if (key == TuiKey.RIGHT || key == TuiKey.TAB) changeTab(1);
        else if (key == TuiKey.SHIFT_TAB) changeTab(-1);
        else if (key == TuiKey.UP) move(-1);
        else if (key == TuiKey.DOWN) move(1);
        else if (key == TuiKey.PAGE_UP) move(-Math.max(1, screen.height() - 7));
        else if (key == TuiKey.PAGE_DOWN) move(Math.max(1, screen.height() - 7));
        else if (key == TuiKey.HOME) moveToBoundary(false);
        else if (key == TuiKey.END) moveToBoundary(true);
        else if (key == TuiKey.ENTER) activate();
        else if (key == TuiKey.DELETE && tab == Tab.CONTEXT) removeSelectedContext();
        else if (key == TuiKey.DELETE && tab == Tab.BREAKPOINTS) clearSelectedBreakpoint();
        else if (key == TuiKey.BACKSPACE || key == TuiKey.DELETE) navigateBack();
        else if (key == ' ' && tab == Tab.CONTEXT) duplicateSelectedContext();
        else if (key == '/') editFilter();
        else if (key == 'n') findNextInView(1);
        else if (key == 'N') findNextInView(-1);
        else if (key == 'g') goToLocation();
        else if (key == '!') statusPage++;
        else if (key == '[') moveHorizontal(-8);
        else if (key == ']') moveHorizontal(8);
        else if (key == 'f') findCurrentList();
        else if (key == 'F') findGlobal();
        else if (key == ':') openExact();
        else if (key == 'p' || key == 'P') goPackage();
        else if (key == 'l') forceLoadClass(true);
        else if (key == 'L') forceLoadClass(false);
        else if (key == 'U' && tab == Tab.BROWSE) toggleUnloadedBrowser();
        else if (key == 'j' || key == 'J') toggleRuntime();
        else if (key == 'k' || key == 'K') toggleSpecialMethods();
        else if (key == 'h' || key == 'H') toggleInheritedObjectMethods();
        else if (key == '@' && (tab == Tab.FIELDS || tab == Tab.METHODS)) toggleStaticMembers();
        else if (key == '#' && (tab == Tab.FIELDS || tab == Tab.METHODS)) toggleVirtualMembers();
        else if (key == 'r' || key == 'R' || key == TuiKey.F5) refresh();
        else if ((key == 's' || key == 'S') && tab == Tab.CONTEXT) swapContextTop();
        else if (key == 'V' && (tab == Tab.BYTECODE || tab == Tab.DEBUG)) requestSourceRange();
        else if (key == 's' || key == 'S') requestSource(false);
        else if (key == 'a' || key == 'A') {
            if (tab == Tab.BREAKPOINTS) clearAllBreakpoints();
            else if (tab == Tab.BROWSE && key == 'a') toggleArrays();
            else if (tab == Tab.BROWSE) requestBrowserClassSource();
            else requestSource(true);
        }
        else if ((key == 'b' || key == 'B') && tab == Tab.FRAMES) {
            openSelectedDebuggerFrame(Tab.BYTECODE);
        }
        else if (key == 'b' || key == 'B') requestSelectedBytecode(Tab.BYTECODE);
        else if (key == 'v' || key == 'V') tab = Tab.CONTEXT;
        else if (key == 'd' || key == 'D') dumpContextClass();
        else if (key == 'u' || key == 'U') toggleSelectedFieldWatch(false);
        else if (key == 'w' || key == 'W') toggleSelectedFieldWatch(true);
        else if (key == 'o' || key == 'O') exportCurrentView();
        else if (key == 'i' || key == 'I') toggleInspector();
        else if (key == 't' || key == 'T') openOrCycleThreads();
        else if (key == 'm' || key == 'M') openLocals();
        else if (key == 'z' || key == 'Z') openBreakpoints();
        else if (key == 'y' || key == 'Y') continueAllExecutions();
        else if (key == '*') toggleAnalysisFreeze();
        else if (key == 'G') jumpToCurrentExecution();
        else if (key == 'e' || key == 'E') toggleEngine();
        else if (key == '=') setSelectedValue();
        else if ((key == 'x' || key == 'X') && tab == Tab.METHODS) invokeSelectedMethod(key == 'X');
        else if (key == '0') resetHorizontal();
        else if (key == 'x' || key == 'X') clearContext();
        else if (key == TuiKey.F9) toggleBreakpoint(false);
        else if (key == TuiKey.SHIFT_F9) toggleBreakpoint(true);
        else if (key == TuiKey.F4) toggleLiveFollow();
        else if (key == TuiKey.F6) pauseSelectedThread();
        else if (key == TuiKey.F7) step();
        else if (key == TuiKey.SHIFT_F7) stepOut();
        else if (key == TuiKey.F8) continueExecution();
        else if (key == TuiKey.CTRL_R && tab == Tab.DEBUG) forceEarlyReturn();
        else if (key == TuiKey.CTRL_E && (tab == Tab.METHODS || tab == Tab.BROWSE)) toggleMethodEventBreakpoint(true);
        else if (key == TuiKey.CTRL_X && (tab == Tab.METHODS || tab == Tab.BROWSE)) toggleMethodEventBreakpoint(false);
        else if (key == TuiKey.CTRL_X && tab == Tab.DEBUG) toggleExceptionBreakpoint();
        else if (key == TuiKey.F3 && (tab == Tab.BYTECODE || tab == Tab.DEBUG)) flushBytecodeEdits();
        else if (key == TuiKey.SHIFT_F3 && (tab == Tab.BYTECODE || tab == Tab.DEBUG)) discardBytecodeEdits();
        else if (key == '+' && (tab == Tab.BYTECODE || tab == Tab.DEBUG)) insertBytecode();
        else if (key == '-' && (tab == Tab.BYTECODE || tab == Tab.DEBUG)) deleteBytecode();
        else if (key == '~' && (tab == Tab.BYTECODE || tab == Tab.DEBUG)) replaceBytecode();
        else if (key == '|' && (tab == Tab.BYTECODE || tab == Tab.DEBUG)) editExceptionHandlers();
        return null;
    }

    private void requestPackage(final String requested) {
        if (browseUnloaded) {
            requestUnloadedPackage(requested, false);
            return;
        }
        final String normalized = requested == null || ".".equals(requested.trim())
                ? "" : requested.trim().replace('/', '.');
        final boolean includeArrays = showArrays && normalized.isEmpty();
        submit("Loading package " + (normalized.isEmpty() ? "<root>" : normalized) + "...",
                new Callable<RemotePackage>() {
                    @Override public RemotePackage call() {
                        RemotePackage value = session.jni().findPackage(normalized);
                        if (!includeArrays) return value;
                        List<String> classNames = new ArrayList<String>(value.classes());
                        for (RemoteClassInfo info : session.jni().searchClasses(
                                new RemoteClassQuery().kind("array").limit(10000))) {
                            classNames.add(info.name());
                        }
                        return new RemotePackage(value.name(), value.packages(), classNames);
                    }
                }, new Consumer<RemotePackage>() {
                    @Override public void accept(RemotePackage value) {
                        browserInitialized = true;
                        packageName = normalized;
                        searchMode = false;
                        browserTitle = "package:" + (normalized.isEmpty() ? "<root>" : normalized);
                        replaceBrowserEntries(TuiBrowserModel.packageEntries(
                                value, showRuntime, showArrays));
                        status = visibleBrowserEntries.size() + " item(s) in " + browserTitle
                                + (showRuntime ? " (runtime visible)" : " (java/jdk/sun hidden)")
                                + (showArrays ? " (array classes visible)" : " (arrays hidden)");
                    }
                });
    }

    private void requestUnloadedPackage(final String requested, final boolean rescan) {
        final String normalized = requested == null || ".".equals(requested.trim())
                ? "" : requested.trim().replace('/', '.');
        submit((rescan ? "Scanning target class path" : "Opening unloaded package") + "...",
                new Callable<UnloadedPackageResult>() {
                    @Override public UnloadedPackageResult call() throws IOException {
                        JvmClassPathCatalog catalog = rescan || classPathCatalog == null
                                ? session.refreshClassPathCatalog() : classPathCatalog;
                        return new UnloadedPackageResult(catalog, catalog.packageView(normalized));
                    }
                }, new Consumer<UnloadedPackageResult>() {
                    @Override public void accept(UnloadedPackageResult result) {
                        classPathCatalog = result.catalog;
                        browserInitialized = true;
                        packageName = normalized;
                        unloadedMemberOwner = "";
                        searchMode = false;
                        browserTitle = "unloaded:" + (normalized.isEmpty() ? "<root>" : normalized);
                        replaceBrowserEntries(TuiBrowserModel.unloadedPackageEntries(
                                result.view, showRuntime));
                        status = visibleBrowserEntries.size() + " item(s) in " + browserTitle
                                + " | " + result.catalog.unloadedSize()
                                + " discoverable unloaded class(es); U returns to live classes";
                    }
                });
    }

    private void toggleUnloadedBrowser() {
        if (tasks.userOperationBusy()) { status = busyMessage(); return; }
        browseUnloaded = !browseUnloaded;
        unloadedMemberOwner = "";
        browserFilter = "";
        if (browseUnloaded) {
            requestUnloadedPackage(packageName, true);
        } else {
            clearUnloadedContext();
            classPathCatalog = null;
            requestPackage(packageName);
            status = "Loaded-class browser enabled; U opens the separate unloaded catalog.";
        }
    }

    private void findGlobal() throws IOException {
        if (tab == Tab.SOURCE || tab == Tab.BYTECODE || tab == Tab.DEBUG) {
            editViewSearch();
            return;
        }
        String query = editText("Find: text | class: | field: | method: | package: (owner#member supported)",
                lastSearch);
        if (query == null || query.trim().isEmpty()) return;
        lastSearch = query.trim();
        requestSearch(lastSearch);
    }

    /** Finds only inside the rows already displayed by the active list. */
    private void findCurrentList() throws IOException {
        if (tab == Tab.SOURCE || tab == Tab.BYTECODE || tab == Tab.DEBUG) {
            editViewSearch();
            return;
        }
        if (tab != Tab.BROWSE && tab != Tab.FIELDS && tab != Tab.METHODS
                && tab != Tab.REFERENCES && tab != Tab.STRINGS) {
            status = "List Find is available in Browse, Fields, Methods, References, String Hooks, Decompile, Bytecode, and Debug.";
            return;
        }
        String value = editText("Find in current list", listSearch);
        if (value == null || value.trim().isEmpty()) return;
        listSearch = value.trim();
        findNextInView(1);
    }

    /** Direct input entry point; exact class/package opens immediately, members use typed search. */
    private void openExact() throws IOException {
        String value = editText("Open: class <name> | package <name> | field <owner>#<name> | method <owner>#<name>", "");
        if (value == null || value.trim().isEmpty()) return;
        String command = value.trim();
        int separator = command.indexOf(' ');
        String kind = separator < 0 ? "class" : command.substring(0, separator).toLowerCase(Locale.ROOT);
        String target = separator < 0 ? command : command.substring(separator + 1).trim();
        if (target.isEmpty()) { status = "An exact target is required."; return; }
        if ("package".equals(kind)) requestPackage(target);
        else if ("field".equals(kind) || "method".equals(kind)) requestSearch(kind + ":" + target);
        else if ("class".equals(kind)) {
            if (browseUnloaded) {
                try {
                    JvmClassPathCatalog.ClassEntry entry = classPathCatalog == null
                            ? null : classPathCatalog.find(target);
                    if (entry == null || classPathCatalog.isLoaded(target)) {
                        status = "No unloaded class named " + target
                                + " is present in the discoverable target class path.";
                    } else requestUnloadedContext(entry);
                } catch (RuntimeException failure) { status = "ERROR: " + rootMessage(failure); }
                return;
            }
            session.context().select(session.findClass(target));
            tab = Tab.CONTEXT;
            status = "Context <- class " + target + "; loading members...";
            requestContextRefresh();
        } else status = "Unknown exact target kind: " + kind;
    }

    private void requestSearch(final String query) {
        if (browseUnloaded) {
            requestUnloadedSearch(query);
            return;
        }
        submit("Searching " + query + "...", new Callable<SearchResult>() {
            @Override public SearchResult call() {
                String lower = query.toLowerCase(Locale.ROOT);
                boolean packagesOnly = lower.startsWith("package:");
                boolean classesOnly = lower.startsWith("class:");
                boolean fieldsOnly = lower.startsWith("field:");
                boolean methodsOnly = lower.startsWith("method:");
                int prefixLength = packagesOnly ? 8 : classesOnly ? 6
                        : fieldsOnly ? 6 : methodsOnly ? 7 : 0;
                String expression = query.substring(prefixLength).trim();
                MemberPattern member = MemberPattern.parse(expression);
                String glob = glob(expression);
                boolean plain = !packagesOnly && !classesOnly && !fieldsOnly && !methodsOnly;
                List<String> packages = packagesOnly || plain
                        ? session.jni().searchPackages(glob, 10000) : Collections.<String>emptyList();
                List<RemoteClassInfo> classes = classesOnly || plain
                        ? session.jni().searchClasses(new RemoteClassQuery().name(glob).limit(10000))
                        : Collections.<RemoteClassInfo>emptyList();
                List<RemoteField> foundFields = fieldsOnly || plain
                        ? session.jni().searchFields(new RemoteMemberQuery()
                                .owner(member.ownerGlob).name(member.nameGlob).limit(10000))
                        : Collections.<RemoteField>emptyList();
                List<RemoteMethod> foundMethods = methodsOnly || plain
                        ? session.jni().searchMethods(new RemoteMemberQuery()
                                .owner(member.ownerGlob).name(member.nameGlob).limit(10000))
                        : Collections.<RemoteMethod>emptyList();
                return new SearchResult(packages, classes, foundFields, foundMethods);
            }
        }, new Consumer<SearchResult>() {
            @Override public void accept(SearchResult result) {
                searchMode = true;
                browserTitle = "find:" + query;
                replaceBrowserEntries(TuiBrowserModel.searchEntries(
                        result.packages, result.classes, result.fields, result.methods,
                        showRuntime, showArrays));
                tab = Tab.BROWSE;
                status = visibleBrowserEntries.size() + " typed search result(s); Enter opens its context; "
                        + "/ filters results";
            }
        });
    }

    private void replaceBrowserEntries(List<TuiBrowserEntry> values) {
        browserEntries.clear();
        browserEntries.addAll(values);
        applyBrowserFilter();
    }

    private void applyBrowserFilter() {
        visibleBrowserEntries.clear();
        visibleBrowserEntries.addAll(TuiBrowserModel.filter(browserEntries, browserFilter));
        selections[Tab.BROWSE.ordinal()] = clamp(
                selections[Tab.BROWSE.ordinal()], 0, Math.max(0, visibleBrowserEntries.size() - 1));
        scrolls[Tab.BROWSE.ordinal()] = 0;
    }

    private void selectBrowserEntry() {
        if (visibleBrowserEntries.isEmpty()) return;
        TuiBrowserEntry entry = visibleBrowserEntries.get(selection());
        if (entry.kind() == TuiBrowserEntry.Kind.PARENT
                || entry.kind() == TuiBrowserEntry.Kind.PACKAGE) {
            requestPackage(entry.name());
            return;
        }
        if (entry.unloaded()) {
            if (entry.kind() == TuiBrowserEntry.Kind.CLASS) {
                pendingMemberResult = null;
                requestUnloadedContext(entry.unloadedClass());
            } else {
                pendingMemberResult = entry;
                requestUnloadedContext(entry.unloadedClass());
            }
            return;
        }
        String owner = entry.ownerName();
        pendingMemberResult = entry.kind() == TuiBrowserEntry.Kind.FIELD
                || entry.kind() == TuiBrowserEntry.Kind.METHOD ? entry : null;
        clearUnloadedContext();
        session.context().select(session.findClass(owner));
        tab = Tab.CONTEXT;
        status = "Context <- class " + owner + "; loading members...";
        requestContextRefresh();
    }

    private void requestUnloadedContext(final JvmClassPathCatalog.ClassEntry owner) {
        if (owner == null) return;
        submit("Opening unloaded class context " + owner.name() + "...",
                new Callable<JvmClassPathCatalog.ClassMetadata>() {
                    @Override public JvmClassPathCatalog.ClassMetadata call() throws IOException {
                        return owner.metadata();
                    }
                }, new Consumer<JvmClassPathCatalog.ClassMetadata>() {
                    @Override public void accept(JvmClassPathCatalog.ClassMetadata metadata) {
                        unloadedContextClass = owner;
                        unloadedFields.clear();
                        unloadedFields.addAll(metadata.fields());
                        unloadedMethods.clear();
                        unloadedMethods.addAll(metadata.methods());
                        memberFilter = "";
                        listSearch = "";
                        selections[Tab.CONTEXT.ordinal()] = 0;
                        selections[Tab.FIELDS.ordinal()] = 0;
                        selections[Tab.METHODS.ordinal()] = 0;
                        contextLines.clear();
                        contextLines.add("Offline context: " + owner.name());
                        contextLines.add("mode          UNLOADED CLASS (class-file metadata)");
                        contextLines.add("origin        " + owner.origin());
                        contextLines.add("super         " + (metadata.superName().isEmpty()
                                ? "<none>" : metadata.superName()));
                        contextLines.add("interfaces    " + metadata.interfaces());
                        contextLines.add("fields        " + metadata.fields().size());
                        contextLines.add("methods       " + metadata.methods().size());
                        contextLines.add("");
                        contextLines.add("Fields, Methods, Decompile and Bytecode work without loading this class.");
                        contextLines.add("Pending breakpoints/watchpoints install automatically at ClassPrepare.");
                        if (pendingMemberResult != null
                                && pendingMemberResult.unloaded()
                                && owner.name().equals(pendingMemberResult.ownerName())
                                && pendingMemberResult.unloadedMember() != null) {
                            JvmClassPathCatalog.Member wanted = pendingMemberResult.unloadedMember();
                            if (wanted.kind() == JvmClassPathCatalog.MemberKind.FIELD) {
                                selections[Tab.FIELDS.ordinal()] = indexOfUnloadedMember(
                                        visibleUnloadedFields(), wanted);
                                tab = Tab.FIELDS;
                            } else {
                                selections[Tab.METHODS.ordinal()] = indexOfUnloadedMember(
                                        visibleUnloadedMethods(), wanted);
                                tab = Tab.METHODS;
                            }
                            pendingMemberResult = null;
                            status = "UNLOADED CLASS context: " + owner.name()
                                    + " | selected catalog member in " + displayTabName(tab);
                        } else {
                            tab = Tab.CONTEXT;
                            status = "UNLOADED CLASS context: " + owner.name()
                                    + " | Tab/Shift+Tab opens Fields and Methods | Backspace returns to its package";
                        }
                    }
                });
    }

    private static int indexOfUnloadedMember(List<JvmClassPathCatalog.Member> values,
            JvmClassPathCatalog.Member wanted) {
        for (int index = 0; index < values.size(); index++) {
            JvmClassPathCatalog.Member value = values.get(index);
            if (value.kind() == wanted.kind() && value.name().equals(wanted.name())
                    && value.descriptor().equals(wanted.descriptor())) return index;
        }
        return 0;
    }

    private void requestContextRefresh() {
        if (!session.context().isSet()) {
            clearContextView();
            synchronizedContextRevision = session.context().revision();
            return;
        }
        final boolean includeSpecialMethods = showSpecialMethods;
        final long requestedRevision = session.context().revision();
        // Context navigation and member filtering are different pieces of state. A TUI
        // instance survives CLI round-trips, so retaining the previous class' member
        // filter would make a correctly loaded new class appear to have no fields or
        // methods. Clear it at the refresh boundary itself; this covers immediate,
        // queued and CLI-triggered refreshes alike.
        if (requestedRevision != synchronizedContextRevision) {
            memberFilter = "";
            listSearch = "";
        }
        final RemoteClass requestedType = session.context().remoteClass();
        final boolean requestedStaticContext = session.context().isClass();
        final String requestedDescription = session.context().description();
        boolean scheduled = submit("Loading context " + requestedDescription + "...",
                new Callable<ContextSnapshot>() {
                    @Override public ContextSnapshot call() {
                        RemoteClass type = requestedType;
                        boolean staticContext = requestedStaticContext;
                        List<RemoteField> loadedFields = new ArrayList<RemoteField>();
                        List<RemoteMethod> loadedMethods = new ArrayList<RemoteMethod>();
                        if (staticContext) {
                            // A class context is also a metadata/debug context. Instance members
                            // do not need a receiver to inspect bytecode or install JVMTI watches.
                            addUniqueFields(loadedFields, type.getStaticFields());
                            addUniqueFields(loadedFields, type.getVirtualFields());
                            addUniqueMethods(loadedMethods, type.getStaticMethods());
                            addUniqueMethods(loadedMethods, type.getVirtualMethods());
                        } else {
                            addUniqueFields(loadedFields, type.getStaticFields());
                            addUniqueFields(loadedFields, type.getVirtualFields());
                            addUniqueMethods(loadedMethods, type.getStaticMethods());
                            addUniqueMethods(loadedMethods, type.getVirtualMethods());
                        }
                        String specialError = "";
                        if (includeSpecialMethods) {
                            try { loadedMethods.addAll(loadJvmSpecialMethods(type)); }
                            catch (RuntimeException failure) { specialError = rootMessage(failure); }
                        }
                        return new ContextSnapshot(type, staticContext, loadedFields, loadedMethods,
                                describeContext(staticContext), specialError);
                    }
                }, new Consumer<ContextSnapshot>() {
                    @Override public void accept(ContextSnapshot value) {
                        scheduledContextRevision = -1L;
                        if (session.context().revision() != requestedRevision) {
                            status = "Context changed while members were loading; opening the latest context...";
                            if (scheduledContextRevision <= requestedRevision) requestContextRefresh();
                            return;
                        }
                        synchronizedContextRevision = requestedRevision;
                        clearUnloadedContext();
                        contextClass = value.type;
                        classContext = value.classContext;
                        fields.clear();
                        fields.addAll(value.fields);
                        methods.clear();
                        methods.addAll(value.methods);
                        sortMembers();
                        contextLines.clear();
                        contextLines.addAll(value.valueLines);
                        preserveSelectedMethod();
                        clampMemberSelections();
                        if (pendingMemberResult != null
                                && pendingMemberResult.ownerName().equals(contextClass.className())) {
                            selectPendingMemberResult();
                        }
                        status = (classContext ? "CLASS/member metadata" : "OBJECT/instance") + " context: "
                                + contextClass.className() + " | stack depth=" + session.context().depth()
                                + (showSpecialMethods ? " | <init>/<clinit> visible" : "")
                                + (value.specialError.isEmpty() ? ""
                                        : " | special methods unavailable: " + value.specialError);
                        if (!browserInitialized) requestPackage("");
                    }
                });
        if (scheduled) scheduledContextRevision = requestedRevision;
    }

    private static void addUniqueFields(List<RemoteField> target, List<RemoteField> source) {
        for (RemoteField candidate : source) {
            if (!containsField(target, candidate)) target.add(candidate);
        }
    }

    private static void addUniqueMethods(List<RemoteMethod> target, List<RemoteMethod> source) {
        for (RemoteMethod candidate : source) {
            if (!containsMethod(target, candidate)) target.add(candidate);
        }
    }

    private void selectPendingMemberResult() {
        TuiBrowserEntry entry = pendingMemberResult;
        pendingMemberResult = null;
        memberFilter = "";
        if (entry.kind() == TuiBrowserEntry.Kind.FIELD) {
            RemoteField wanted = entry.field();
            if (!containsField(fields, wanted)) fields.add(wanted);
            sortMembers();
            selections[Tab.FIELDS.ordinal()] = Math.max(0, indexOfField(visibleFields(), wanted));
            tab = Tab.FIELDS;
        } else if (entry.kind() == TuiBrowserEntry.Kind.METHOD) {
            RemoteMethod wanted = entry.method();
            if (!containsMethod(methods, wanted)) methods.add(wanted);
            sortMembers();
            selections[Tab.METHODS.ordinal()] = Math.max(0, indexOfMethod(visibleMethods(), wanted));
            List<RemoteMethod> visible = visibleMethods();
            selectedMethod = visible.isEmpty() ? null
                    : visible.get(selections[Tab.METHODS.ordinal()]);
            tab = Tab.METHODS;
        }
    }

    private static boolean containsField(List<RemoteField> values, RemoteField wanted) {
        return indexOfField(values, wanted) >= 0;
    }

    private static int indexOfField(List<RemoteField> values, RemoteField wanted) {
        for (int index = 0; index < values.size(); index++) {
            RemoteField value = values.get(index);
            if (value.declaringClass().equals(wanted.declaringClass())
                    && value.name().equals(wanted.name())
                    && value.descriptor().equals(wanted.descriptor())) return index;
        }
        return -1;
    }

    private static boolean containsMethod(List<RemoteMethod> values, RemoteMethod wanted) {
        return indexOfMethod(values, wanted) >= 0;
    }

    private static int indexOfMethod(List<RemoteMethod> values, RemoteMethod wanted) {
        for (int index = 0; index < values.size(); index++) {
            if (sameMethod(values.get(index), wanted)) return index;
        }
        return -1;
    }

    private static List<RemoteMethod> loadJvmSpecialMethods(RemoteClass type) {
        List<RemoteMethod> result = new ArrayList<RemoteMethod>();
        for (nhcm.jvmrtdp.controllerside.analysis.ClassFileMethod method
                : type.classFileView().methods()) {
            if ("<init>".equals(method.name()) || "<clinit>".equals(method.name())) {
                result.add(RemoteMethod.jvmSpecial(type, method.name(), method.descriptor(),
                        method.accessFlags()));
            }
        }
        return result;
    }

    private List<String> describeContext(boolean staticContext) {
        List<String> result = new ArrayList<String>();
        result.add("Context stack top: " + session.context().description());
        result.add("mode          " + (staticContext ? "CLASS (static + virtual member metadata)"
                : "OBJECT (instance fields/methods)"));
        result.add("view type     " + session.context().remoteClass().className());
        result.add("stack depth   " + session.context().depth());
        if (staticContext) {
            result.add("");
            result.add("Static and virtual members are searchable without an object reference.");
            result.add("Enter reads static fields; instance fields need an OBJECT context.");
            result.add("U/W can watch instance field access/write directly from this CLASS context.");
            return result;
        }
        RemoteObject object = session.context().remoteObject();
        RemoteObjectDebugInfo debug = object.debugInfo();
        result.add("runtime type  " + debug.className());
        result.add("shape         " + debug.shape());
        result.add("size          " + (debug.size().isEmpty() ? "n/a" : debug.size()));
        result.add("identity      0x" + debug.identityHash());
        result.add("display       " + debug.displayValue());
        addValuePreview(result, object, debug);
        return result;
    }

    private static void addValuePreview(List<String> lines, RemoteObject object, RemoteObjectDebugInfo debug) {
        lines.add("");
        if ("array".equals(debug.shape())) {
            int size = object.arrayLength();
            lines.add("Array preview:");
            for (int index = 0; index < Math.min(size, 16); index++) {
                try (RemoteObject value = object.arrayGet(index)) {
                    lines.add(String.format("  [%d] %s", index, value));
                }
            }
            if (size > 16) lines.add("  ... " + (size - 16) + " more element(s)");
        } else if ("map".equals(debug.shape())) {
            lines.add("Map preview:");
            List<RemoteMapEntry> entries = object.mapEntries(16);
            try {
                for (RemoteMapEntry entry : entries) lines.add("  " + entry.key() + " => " + entry.value());
            } finally {
                for (RemoteMapEntry entry : entries) entry.close();
            }
        } else if ("iterable".equals(debug.shape())) {
            lines.add("Iterable preview:");
            List<RemoteObject> elements = object.iterableElements(16);
            try {
                for (int index = 0; index < elements.size(); index++) {
                    lines.add(String.format("  [%d] %s", index, elements.get(index)));
                }
            } finally {
                for (RemoteObject element : elements) element.close();
            }
        }
    }

    private void sortMembers() {
        Collections.sort(fields, Comparator.comparing(RemoteField::name)
                .thenComparing(RemoteField::declaringClass).thenComparing(RemoteField::descriptor));
        Collections.sort(methods, Comparator.comparing(RemoteMethod::name)
                .thenComparing(RemoteMethod::declaringClass).thenComparing(RemoteMethod::descriptor));
    }

    private void preserveSelectedMethod() {
        if (selectedMethod == null) return;
        for (RemoteMethod candidate : methods) {
            if (sameMethod(candidate, selectedMethod)) {
                selectedMethod = candidate;
                return;
            }
        }
        selectedMethod = null;
    }

    private void activate() {
        // Context navigation is versioned and may safely queue behind a member load.
        // This keeps Enter responsive when the previous class is still refreshing.
        if (tasks.userOperationBusy() && tab != Tab.BROWSE && tab != Tab.CONTEXT) {
            status = busyMessage();
            return;
        }
        if (tab == Tab.BROWSE) selectBrowserEntry();
        else if (tab == Tab.CONTEXT) selectContextStackItem();
        else if (tab == Tab.REFERENCES) useSelectedReference();
        else if (tab == Tab.FIELDS) readSelectedField();
        else if (tab == Tab.METHODS) requestSelectedBytecode(Tab.BYTECODE);
        else if (tab == Tab.SOURCE) jumpSourceLineToBytecode();
        else if (tab == Tab.FRAMES) openSelectedDebuggerFrame(Tab.DEBUG);
        else if (tab == Tab.LOCALS) selectLocalAsContext();
        else if (tab == Tab.BREAKPOINTS) openSelectedBreakpoint();
        else if (tab == Tab.THREADS) selectOrPauseThread();
        else if (tab == Tab.STRINGS) openSelectedStringHook();
        else if (tab == Tab.BYTECODE || tab == Tab.DEBUG) toggleBreakpoint(false);
    }

    private void jumpSourceLineToBytecode() {
        if (sourceMethod.isEmpty() || sourceBciToLine.isEmpty()) {
            status = "This Decompile view has no method-level BCI mapping; decompile one method with CFR.";
            return;
        }
        int selectedLine = selections[Tab.SOURCE.ordinal()] + 1;
        Map.Entry<Integer, Integer> best = nearestSourceMapping(selectedLine);
        if (best == null) { status = "No bytecode mapping exists near this decompiled line."; return; }
        final int bci = best.getKey();
        requestBytecode(sourceClass, sourceMethod, sourceDescriptor, Tab.BYTECODE);
        // The bytecode task applies this cursor after loading via the pending location.
        pendingBytecodeLocation = bci;
        status = "Loading bytecode for decompiled line " + selectedLine + " -> BCI " + bci;
    }

    private Map.Entry<Integer, Integer> nearestSourceMapping(int selectedLine) {
        Map.Entry<Integer, Integer> best = null;
        int distance = Integer.MAX_VALUE;
        for (Map.Entry<Integer, Integer> mapping : sourceBciToLine.entrySet()) {
            int candidate = Math.abs(mapping.getValue() - selectedLine);
            if (candidate < distance) { best = mapping; distance = candidate; }
        }
        return best;
    }

    private void selectContextStackItem() {
        if (unloadedContextClass != null) {
            status = "This offline class context is already selected; Tab opens Fields, then Methods.";
            return;
        }
        if (!session.context().isSet()) return;
        int index = clamp(selection(), 0, Math.max(0, session.context().depth() - 1));
        if (index == 0) return;
        session.context().moveToTop(index);
        selections[Tab.CONTEXT.ordinal()] = 0;
        requestContextRefresh();
    }

    private void duplicateSelectedContext() {
        if (unloadedContextClass != null) {
            status = "Offline class context is a single class-file view and has no object handle to duplicate.";
            return;
        }
        if (!session.context().isSet()) return;
        int index = clamp(selections[Tab.CONTEXT.ordinal()], 0, session.context().depth() - 1);
        session.context().pick(index);
        selections[Tab.CONTEXT.ordinal()] = 0;
        status = "Copied context stack item #" + index + " to the top";
        requestContextRefresh();
    }

    private void removeSelectedContext() {
        if (unloadedContextClass != null) {
            navigateBack();
            return;
        }
        if (!session.context().isSet()) return;
        int index = clamp(selections[Tab.CONTEXT.ordinal()], 0, session.context().depth() - 1);
        session.context().remove(index);
        selections[Tab.CONTEXT.ordinal()] = clamp(index, 0,
                Math.max(0, session.context().depth() - 1));
        if (session.context().isSet()) requestContextRefresh();
        else {
            clearContextView();
            status = "Context stack is empty";
        }
    }

    private void swapContextTop() {
        if (unloadedContextClass != null) {
            status = "Offline class context has one item; Backspace returns to its package.";
            return;
        }
        if (!session.context().isSet() || session.context().depth() < 2) {
            status = "Context stack has no second item to swap";
            return;
        }
        session.context().swap();
        selections[Tab.CONTEXT.ordinal()] = 0;
        requestContextRefresh();
    }

    private void openLocals() {
        tab = Tab.LOCALS;
        selections[Tab.LOCALS.ordinal()] = clamp(selections[Tab.LOCALS.ordinal()], 0,
                Math.max(0, debuggerLocals.size() - 1));
        status = debuggerState != null && debuggerState.paused()
                ? "Frame #" + debuggerFrameDepth
                        + " locals; Enter pushes an available value onto Context"
                : liveSampleAvailable
                        ? "Live sample frame #" + debuggerFrameDepth
                                + " locals; Enter pushes an available value onto Context"
                        : "Locals require a paused thread or a live-follow sample";
        if (!liveSampleAvailable && !tasks.busy()) requestDebuggerRefresh();
    }

    private void openSelectedDebuggerFrame(Tab destination) {
        boolean paused = debuggerState != null && debuggerState.paused();
        if ((!paused && !liveSampleAvailable) || debuggerFrames.isEmpty()) {
            status = "Pause a Java thread or enable live follow, then select a stack frame.";
            return;
        }
        final JvmStackFrame selected = debuggerFrames.get(clamp(
                selections[Tab.FRAMES.ordinal()], 0, debuggerFrames.size() - 1));
        selectDebuggerFrame(selected);
        if (!selected.hasJavaLocation()) {
            status = "Frame #" + selected.depth()
                    + " is native and has no Java bytecode/BCI; select a Java caller below it.";
            return;
        }
        if (!paused) {
            liveFollowFrameDepth = selected.depth();
            pendingBytecodeLocation = (int) selected.location();
            requestBytecode(selected.className(), selected.methodName(),
                    selected.descriptor(), destination);
            status = "Following frame #" + selected.depth()
                    + "; its bytecode and locals refresh on the next live sample";
            return;
        }
        submit("Opening frame #" + selected.depth() + " at BCI " + selected.location() + "...",
                new Callable<DebuggerSnapshot>() {
                    @Override public DebuggerSnapshot call() { return debuggerSnapshot(); }
                }, new Consumer<DebuggerSnapshot>() {
                    @Override public void accept(DebuggerSnapshot value) {
                        applyDebuggerSnapshot(value, false);
                        JvmStackFrame current = viewedDebuggerFrame();
                        if (current == null || !current.hasJavaLocation()) {
                            status = "The selected frame disappeared while refreshing the thread.";
                            return;
                        }
                        pendingBytecodeLocation = (int) current.location();
                        requestBytecode(current.className(), current.methodName(),
                                current.descriptor(), destination);
                    }
                });
    }

    private void requestUnloadedSearch(final String query) {
        submit("Searching unloaded catalog " + query + "...",
                new Callable<List<TuiBrowserEntry>>() {
                    @Override public List<TuiBrowserEntry> call() throws IOException {
                        JvmClassPathCatalog catalog = classPathCatalog == null
                                ? session.refreshClassPathCatalog() : classPathCatalog;
                        classPathCatalog = catalog;
                        String lower = query.toLowerCase(Locale.ROOT);
                        boolean fields = lower.startsWith("field:");
                        boolean methods = lower.startsWith("method:");
                        int prefix = fields ? 6 : methods ? 7
                                : lower.startsWith("class:") ? 6
                                : lower.startsWith("package:") ? 8 : 0;
                        String expression = query.substring(prefix).trim();
                        if (fields || methods) {
                            MemberPattern pattern = MemberPattern.parse(expression);
                            return TuiBrowserModel.unloadedMemberSearchEntries(
                                    catalog.searchUnloadedMembers(pattern.ownerGlob,
                                            pattern.nameGlob, fields
                                                    ? JvmClassPathCatalog.MemberKind.FIELD
                                                    : JvmClassPathCatalog.MemberKind.METHOD,
                                            10000), showRuntime);
                        }
                        String search = lower.startsWith("package:")
                                ? glob(expression) + ".*" : glob(expression);
                        return TuiBrowserModel.unloadedSearchEntries(
                                catalog.searchUnloaded(search, 10000), showRuntime);
                    }
                }, new Consumer<List<TuiBrowserEntry>>() {
                    @Override public void accept(List<TuiBrowserEntry> entries) {
                        searchMode = true;
                        unloadedMemberOwner = "";
                        browserTitle = "unloaded-find:" + query;
                        replaceBrowserEntries(entries);
                        tab = Tab.BROWSE;
                        status = visibleBrowserEntries.size()
                                + " unloaded search result(s); [U:*] rows are not loaded";
                    }
                });
    }

    private JvmStackFrame viewedDebuggerFrame() {
        if (debuggerFrames.isEmpty()) return null;
        for (JvmStackFrame frame : debuggerFrames) {
            if (frame.depth() == debuggerFrameDepth) return frame;
        }
        return debuggerFrames.get(clamp(debuggerFrameDepth, 0, debuggerFrames.size() - 1));
    }

    private JvmStackFrame viewedOrSelectedDebuggerFrame() {
        if (debuggerFrames.isEmpty()) return null;
        if (tab == Tab.FRAMES) {
            return debuggerFrames.get(clamp(selections[Tab.FRAMES.ordinal()],
                    0, debuggerFrames.size() - 1));
        }
        return viewedDebuggerFrame();
    }

    private void selectDebuggerFrame(JvmStackFrame frame) {
        if (frame == null) return;
        debuggerFrameDepth = frame.depth();
        for (int index = 0; index < debuggerFrames.size(); index++) {
            if (debuggerFrames.get(index).depth() == frame.depth()) {
                selections[Tab.FRAMES.ordinal()] = index;
                return;
            }
        }
    }

    private void selectLocalAsContext() {
        boolean readable = debuggerState != null && debuggerState.paused() || liveSampleAvailable;
        if (!readable || debuggerLocals.isEmpty()) {
            status = "No paused/live-sampled frame local is available";
            return;
        }
        int index = clamp(selections[Tab.LOCALS.ordinal()], 0, debuggerLocals.size() - 1);
        JvmDebuggerLocal local = debuggerLocals.get(index);
        if (!local.available() || local.value() == null) {
            status = "Local " + local.name() + " is unavailable: " + local.error();
            return;
        }
        // Transfer this remote handle out of the refresh-owned locals list. Otherwise the
        // next debugger snapshot would close the object now retained by RemoteContext.
        debuggerLocals.remove(index);
        if (debuggerState != null && debuggerState.paused()) {
            session.context().select(local.value(), null,
                    session.operations().debuggerLocalAssignment(debuggerState.sequence(),
                            debuggerFrameDepth, local.slot(), local.descriptor()));
        } else session.context().select(local.value());
        tab = Tab.CONTEXT;
        selections[Tab.CONTEXT.ordinal()] = 0;
        status = "Context <- local [" + local.slot() + "] " + local.name();
        requestContextRefresh();
    }

    private void openBreakpoints() {
        tab = Tab.BREAKPOINTS;
        selections[Tab.BREAKPOINTS.ordinal()] = clamp(selections[Tab.BREAKPOINTS.ordinal()], 0,
                Math.max(0, breakpoints.size() - 1));
        status = breakpoints.isEmpty() ? "No managed breakpoints; use F9 in Methods/Bytecode/Decompile"
                : "Enter opens a breakpoint; F9/Delete clears it; A clears all";
    }

    private List<BreakpointSpec> breakpointList() {
        return new ArrayList<BreakpointSpec>(breakpoints.values());
    }

    private void openSelectedBreakpoint() {
        List<BreakpointSpec> values = breakpointList();
        if (values.isEmpty()) return;
        BreakpointSpec selected = values.get(clamp(selections[Tab.BREAKPOINTS.ordinal()], 0,
                values.size() - 1));
        pendingBytecodeLocation = (int) selected.bci;
        requestBytecode(selected.className, selected.methodName, selected.descriptor, Tab.BYTECODE);
    }

    private void clearSelectedBreakpoint() {
        List<BreakpointSpec> values = breakpointList();
        if (values.isEmpty()) { status = "No breakpoint is selected"; return; }
        final BreakpointSpec selected = values.get(clamp(selections[Tab.BREAKPOINTS.ordinal()], 0,
                values.size() - 1));
        submit("Clearing breakpoint " + selected.methodName + " @" + selected.bci + "...",
                new Callable<Boolean>() {
                    @Override public Boolean call() {
                        session.jvmti().clearBreakpoint(selected.info());
                        return Boolean.TRUE;
                    }
                }, new Consumer<Boolean>() {
                    @Override public void accept(Boolean ignored) {
                        breakpoints.remove(selected.id());
                        selections[Tab.BREAKPOINTS.ordinal()] = clamp(
                                selections[Tab.BREAKPOINTS.ordinal()], 0,
                                Math.max(0, breakpoints.size() - 1));
                        status = "Breakpoint cleared: " + selected.className + "."
                                + selected.methodName + " @BCI " + selected.bci;
                    }
                });
    }

    private void clearAllBreakpoints() {
        if (breakpoints.isEmpty()) { status = "No managed breakpoints to clear"; return; }
        final List<BreakpointSpec> values = breakpointList();
        submit("Clearing " + values.size() + " breakpoints...", new Callable<BreakpointClearResult>() {
            @Override public BreakpointClearResult call() {
                List<String> cleared = new ArrayList<String>();
                List<String> failures = new ArrayList<String>();
                for (BreakpointSpec selected : values) {
                    try {
                        session.jvmti().clearBreakpoint(selected.info());
                        cleared.add(selected.id());
                    } catch (RuntimeException failure) {
                        failures.add(selected.methodName + " @" + selected.bci + ": "
                                + rootMessage(failure));
                    }
                }
                return new BreakpointClearResult(cleared, failures);
            }
        }, new Consumer<BreakpointClearResult>() {
            @Override public void accept(BreakpointClearResult result) {
                for (String id : result.cleared) breakpoints.remove(id);
                selections[Tab.BREAKPOINTS.ordinal()] = clamp(
                        selections[Tab.BREAKPOINTS.ordinal()], 0,
                        Math.max(0, breakpoints.size() - 1));
                status = result.failures.isEmpty()
                        ? "Cleared " + result.cleared.size() + " managed breakpoint(s)"
                        : "Cleared " + result.cleared.size() + "; "
                                + result.failures.size() + " failed: " + result.failures.get(0);
            }
        });
    }

    private void readSelectedField() {
        if (unloadedContextClass != null) {
            JvmClassPathCatalog.Member field = selectedUnloadedField();
            if (field != null) status = "UNLOADED field metadata: " + unloadedContextClass.name()
                    + "." + field.name() + " " + field.descriptor()
                    + "; U/W registers pending read/write watches; L loads the class when desired.";
            return;
        }
        final List<RemoteField> visible = visibleFields();
        if (visible.isEmpty()) return;
        final RemoteField field = visible.get(selection());
        if (!field.isStatic() && session.context().isClass()) {
            status = "This is an instance field. Select an object context before reading it; "
                    + "its metadata is still available here.";
            return;
        }
        final RemoteObject receiver = field.isStatic() ? null : session.context().remoteObject();
        submit("Reading " + field.declaringClass() + "." + field.name() + "...",
                new Callable<RemoteObject>() {
                    @Override public RemoteObject call() {
                        return session.operations().read(field, receiver);
                    }
                }, new Consumer<RemoteObject>() {
                    @Override public void accept(RemoteObject value) {
                        session.context().select(value, null,
                                session.operations().fieldAssignment(field, receiver));
                        tab = Tab.CONTEXT;
                        status = "Context <- " + field.declaringClass() + "." + field.name();
                        requestContextRefresh();
                    }
                });
    }

    private void setSelectedValue() throws IOException {
        if (tab == Tab.FIELDS) setSelectedField();
        else if (tab == Tab.LOCALS) setSelectedLocal();
        else if (tab == Tab.CONTEXT) setCurrentContext();
        else if (tab == Tab.REFERENCES) setSelectedReference();
        else if (tab == Tab.STRINGS) setSelectedStringHookValue();
        else status = "Use = in Context, References, String Hooks, Fields, or Locals. Methods/classes use redefine instead.";
    }

    private void saveCurrentReference(boolean weak) throws IOException {
        if (!session.context().isSet() || session.context().isClass()) {
            status = "Select an object context before saving a tracked reference.";
            return;
        }
        final String name = editText("Reference name", "ref" + (session.references().snapshot().size() + 1));
        if (name == null || name.trim().isEmpty()) return;
        final RemoteObject object = session.context().remoteObject();
        final JvmReferenceStrength strength = weak
                ? JvmReferenceStrength.WEAK : JvmReferenceStrength.STRONG;
        submit("Saving " + strength.name().toLowerCase(Locale.ROOT) + " reference " + name + "...",
                new Callable<JvmReferenceInfo>() {
                    @Override public JvmReferenceInfo call() {
                        return session.references().trackObject(name, object, strength);
                    }
                }, new Consumer<JvmReferenceInfo>() {
                    @Override public void accept(JvmReferenceInfo value) {
                        tab = Tab.REFERENCES;
                        selectReference(value.name());
                        status = "Saved " + value;
                    }
                });
    }

    private void trackSelectedFieldReference() throws IOException {
        if (unloadedContextClass != null) {
            status = "Unloaded fields have metadata but no value to track until the class is prepared.";
            return;
        }
        List<RemoteField> visible = visibleFields();
        if (visible.isEmpty()) { status = "No field is selected."; return; }
        final RemoteField field = visible.get(selection());
        if (!field.isStatic() && session.context().isClass()) {
            status = "Select an object context to track this instance field.";
            return;
        }
        final String name = editText("Tracked field reference name", field.name());
        if (name == null || name.trim().isEmpty()) return;
        String policy = field.isStatic() ? "strong" : editText("Receiver lifetime: strong or weak", "strong");
        if (policy == null) return;
        final JvmReferenceStrength strength = "weak".equalsIgnoreCase(policy.trim())
                ? JvmReferenceStrength.WEAK : JvmReferenceStrength.STRONG;
        if (!"strong".equalsIgnoreCase(policy.trim()) && !"weak".equalsIgnoreCase(policy.trim())) {
            status = "Receiver lifetime must be strong or weak.";
            return;
        }
        final RemoteObject receiver = field.isStatic() ? null : session.context().remoteObject();
        submit("Tracking field " + field.declaringClass() + "." + field.name() + "...",
                new Callable<JvmReferenceInfo>() {
                    @Override public JvmReferenceInfo call() {
                        return field.isStatic()
                                ? session.references().trackStaticField(name, field)
                                : session.references().trackField(name, field, receiver, strength);
                    }
                }, new Consumer<JvmReferenceInfo>() {
                    @Override public void accept(JvmReferenceInfo value) {
                        tab = Tab.REFERENCES;
                        selectReference(value.name());
                        status = "Tracking " + value.source();
                    }
                });
    }

    private void requestReferenceRefresh() {
        submit("Refreshing tracked references...", new Callable<List<JvmReferenceInfo>>() {
            @Override public List<JvmReferenceInfo> call() { return session.references().refreshAll(); }
        }, new Consumer<List<JvmReferenceInfo>>() {
            @Override public void accept(List<JvmReferenceInfo> values) {
                selections[Tab.REFERENCES.ordinal()] = clamp(selections[Tab.REFERENCES.ordinal()],
                        0, Math.max(0, values.size() - 1));
                status = "Refreshed " + values.size() + " tracked reference(s).";
            }
        });
    }

    private JvmReferenceInfo selectedReference() {
        List<JvmReferenceInfo> values = session.references().snapshot();
        return values.isEmpty() ? null : values.get(clamp(
                selections[Tab.REFERENCES.ordinal()], 0, values.size() - 1));
    }

    private void selectReference(String name) {
        List<JvmReferenceInfo> values = session.references().snapshot();
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index).name().equals(name)) {
                selections[Tab.REFERENCES.ordinal()] = index;
                return;
            }
        }
    }

    private void useSelectedReference() {
        final JvmReferenceInfo selected = selectedReference();
        if (selected == null) return;
        submit("Acquiring " + selected.name() + " as Context...", new Callable<RemoteObject>() {
            @Override public RemoteObject call() { return session.references().acquire(selected.name()); }
        }, new Consumer<RemoteObject>() {
            @Override public void accept(RemoteObject value) {
                session.context().select(value);
                tab = Tab.CONTEXT;
                status = "Context <- tracked reference " + selected.name();
                requestContextRefresh();
            }
        });
    }

    private void setSelectedReference() throws IOException {
        final JvmReferenceInfo selected = selectedReference();
        if (selected == null) return;
        final String expression = editText("Replace tracked reference " + selected.name(), "null");
        if (expression == null) return;
        submit("Updating tracked reference " + selected.name() + "...",
                new Callable<JvmReferenceInfo>() {
                    @Override public JvmReferenceInfo call() {
                        try (RemoteArgumentList value = RemoteArgumentList.resolve(
                                session, Collections.singletonList(expression))) {
                            return session.references().replace(selected.name(), value.only());
                        }
                    }
                }, new Consumer<JvmReferenceInfo>() {
                    @Override public void accept(JvmReferenceInfo value) { status = "Updated " + value; }
                });
    }

    private void nullSelectedReference() {
        final JvmReferenceInfo selected = selectedReference();
        if (selected == null) return;
        submit("Setting " + selected.name() + " to null...", new Callable<JvmReferenceInfo>() {
            @Override public JvmReferenceInfo call() { return session.references().setNull(selected.name()); }
        }, new Consumer<JvmReferenceInfo>() {
            @Override public void accept(JvmReferenceInfo value) { status = "Updated " + value; }
        });
    }

    private void releaseSelectedReference() {
        final JvmReferenceInfo selected = selectedReference();
        if (selected == null) return;
        submit("Releasing tracked reference " + selected.name() + "...", new Callable<String>() {
            @Override public String call() {
                session.references().release(selected.name());
                return selected.name();
            }
        }, new Consumer<String>() {
            @Override public void accept(String name) {
                selections[Tab.REFERENCES.ordinal()] = clamp(selections[Tab.REFERENCES.ordinal()],
                        0, Math.max(0, session.references().snapshot().size() - 1));
                status = "Released tracked reference " + name;
            }
        });
    }

    private JvmStringHookInfo selectedStringHook() {
        List<JvmStringHookInfo> values = session.stringHooks().snapshot();
        return values.isEmpty() ? null : values.get(clamp(
                selections[Tab.STRINGS.ordinal()], 0, values.size() - 1));
    }

    private void addStringHook() throws IOException {
        String source = editText("String hook: allocation <name> <content-glob> [class method descriptor] [fast|complete] [ldc|no-ldc] [ignore-case] [once|max=N] [sample=N] | field <name> <read|write> <class> <field> [object] | method <name> <entry|exit> <class> <method> <descriptor>", "");
        if (source == null || source.trim().isEmpty()) return;
        final CommandLine line = CommandLine.parse(source);
        final List<String> arguments = line.arguments();
        final String operation = line.name().toLowerCase(Locale.ROOT);
        submit("Installing String hook...", new Callable<JvmStringHookInfo>() {
            @Override public JvmStringHookInfo call() {
                if ("field".equals(operation) && (arguments.size() == 4 || arguments.size() == 5)) {
                    boolean write = "write".equalsIgnoreCase(arguments.get(1));
                    if (!write && !"read".equalsIgnoreCase(arguments.get(1))) {
                        throw new IllegalArgumentException("Field hook mode must be read or write");
                    }
                    RemoteField field = findStringField(session.findClass(arguments.get(2)), arguments.get(3));
                    boolean objectSpecific = arguments.size() == 5 && "object".equalsIgnoreCase(arguments.get(4));
                    if (arguments.size() == 5 && !objectSpecific) {
                        throw new IllegalArgumentException("Optional field hook scope must be object");
                    }
                    return session.stringHooks().watchField(arguments.get(0), field, write,
                            objectSpecific ? session.context().remoteObject() : null);
                }
                if ("method".equals(operation) && arguments.size() == 5) {
                    JvmStringHookKind kind = "entry".equalsIgnoreCase(arguments.get(1))
                            ? JvmStringHookKind.METHOD_ENTRY : "exit".equalsIgnoreCase(arguments.get(1))
                            ? JvmStringHookKind.METHOD_EXIT : null;
                    if (kind == null) throw new IllegalArgumentException("Method hook mode must be entry or exit");
                    return session.stringHooks().breakMethod(arguments.get(0), kind,
                            arguments.get(2), arguments.get(3), arguments.get(4));
                }
                if ("allocation".equals(operation) && arguments.size() >= 2) {
                    JvmStringAllocationSpec spec = StringAllocationSpecParser.parse(arguments, 1);
                    return session.stringHooks().breakAllocation(arguments.get(0), spec);
                }
                throw new IllegalArgumentException(
                        "Expected allocation ..., field ..., or method ... String hook syntax");
            }
        }, new Consumer<JvmStringHookInfo>() {
            @Override public void accept(JvmStringHookInfo value) {
                selectStringHook(value.name());
                status = "Installed " + value;
            }
        });
    }

    private void hookSelectedStringField() throws IOException {
        if (unloadedContextClass != null) { status = "Load the class before creating a runtime String hook."; return; }
        List<RemoteField> visible = visibleFields();
        if (visible.isEmpty()) return;
        final RemoteField field = visible.get(selection());
        if (!"Ljava/lang/String;".equals(field.descriptor())) {
            status = "Selected field is not java.lang.String.";
            return;
        }
        final String name = editText("String hook name", field.name() + "-string");
        if (name == null || name.trim().isEmpty()) return;
        String mode = editText("Pause on read or write", "write");
        if (mode == null) return;
        final boolean write = "write".equalsIgnoreCase(mode.trim());
        if (!write && !"read".equalsIgnoreCase(mode.trim())) { status = "Mode must be read or write."; return; }
        String scope = field.isStatic() ? "all" : editText("Scope: all instances or this object", "all");
        if (scope == null) return;
        final boolean objectSpecific = "object".equalsIgnoreCase(scope.trim())
                || "this".equalsIgnoreCase(scope.trim());
        if (!field.isStatic() && objectSpecific && session.context().isClass()) {
            status = "Object-specific String hooks require an object context.";
            return;
        }
        final RemoteObject receiver = !field.isStatic() && objectSpecific
                ? session.context().remoteObject() : null;
        submit("Installing String field hook...", new Callable<JvmStringHookInfo>() {
            @Override public JvmStringHookInfo call() {
                return session.stringHooks().watchField(name, field, write, receiver);
            }
        }, new Consumer<JvmStringHookInfo>() {
            @Override public void accept(JvmStringHookInfo value) {
                tab = Tab.STRINGS;
                selectStringHook(value.name());
                status = "Installed " + value;
            }
        });
    }

    private void selectStringHook(String name) {
        List<JvmStringHookInfo> values = session.stringHooks().snapshot();
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index).name().equals(name)) {
                selections[Tab.STRINGS.ordinal()] = index;
                return;
            }
        }
    }

    private void toggleSelectedStringHook() {
        final JvmStringHookInfo selected = selectedStringHook();
        if (selected == null) return;
        final boolean rearm = selected.exhausted();
        submit((rearm ? "Rearming " : selected.enabled() ? "Disabling " : "Enabling ")
                        + selected.name() + "...",
                new Callable<JvmStringHookInfo>() {
                    @Override public JvmStringHookInfo call() {
                        return rearm ? session.stringHooks().rearm(selected.name())
                                : session.stringHooks().setEnabled(selected.name(), !selected.enabled());
                    }
                }, new Consumer<JvmStringHookInfo>() {
                    @Override public void accept(JvmStringHookInfo value) { status = value.toString(); }
                });
    }

    private void removeSelectedStringHook() {
        final JvmStringHookInfo selected = selectedStringHook();
        if (selected == null) return;
        submit("Removing String hook " + selected.name() + "...", new Callable<String>() {
            @Override public String call() { session.stringHooks().remove(selected.name()); return selected.name(); }
        }, new Consumer<String>() {
            @Override public void accept(String name) {
                selections[Tab.STRINGS.ordinal()] = clamp(selections[Tab.STRINGS.ordinal()], 0,
                        Math.max(0, session.stringHooks().snapshot().size() - 1));
                status = "Removed String hook " + name;
            }
        });
    }

    private void openSelectedStringHook() {
        final JvmStringHookInfo selected = selectedStringHook();
        if (selected == null) return;
        if (!selected.fieldHook() && !selected.allocationHook()) {
            if (selected.lastHitSequence() < 0) {
                status = "This method hook has not fired yet; its next hit will appear in Debug.";
            } else {
                tab = Tab.DEBUG;
                alignDebuggerLocation(Tab.DEBUG);
                status = "Opened last String hook hit: " + selected.lastHit();
            }
            return;
        }
        submit("Reading String hook value " + selected.name() + "...", new Callable<RemoteObject>() {
            @Override public RemoteObject call() { return session.stringHooks().acquireValue(selected.name()); }
        }, new Consumer<RemoteObject>() {
            @Override public void accept(RemoteObject value) {
                session.context().select(value);
                tab = Tab.CONTEXT;
                status = "Context <- String hook " + selected.name()
                        + "; Methods can invoke code on this String.";
                requestContextRefresh();
            }
        });
    }

    private void setSelectedStringHookValue() throws IOException {
        final JvmStringHookInfo selected = selectedStringHook();
        if (selected == null || !selected.fieldHook()) {
            status = "Only field-backed String hooks have a replaceable value.";
            return;
        }
        final String expression = editText("Replace String field value", "string:");
        if (expression == null) return;
        submit("Replacing String hook value...", new Callable<String>() {
            @Override public String call() {
                try (RemoteArgumentList value = RemoteArgumentList.resolve(
                        session, Collections.singletonList(expression))) {
                    session.stringHooks().replaceValue(selected.name(), value.only());
                }
                return selected.name();
            }
        }, new Consumer<String>() {
            @Override public void accept(String name) { status = "Updated String field for hook " + name; }
        });
    }

    private void trackSelectedStringHookValue() throws IOException {
        final JvmStringHookInfo selected = selectedStringHook();
        if (selected == null || (!selected.fieldHook() && !selected.allocationHook())) {
            status = "Only field and allocation String hooks can become tracked values.";
            return;
        }
        final String name = editText("Tracked reference name", selected.name());
        if (name == null || name.trim().isEmpty()) return;
        submit("Tracking String hook value...", new Callable<JvmReferenceInfo>() {
            @Override public JvmReferenceInfo call() {
                return session.stringHooks().trackValue(selected.name(), session.references(),
                        name, JvmReferenceStrength.STRONG);
            }
        }, new Consumer<JvmReferenceInfo>() {
            @Override public void accept(JvmReferenceInfo value) {
                tab = Tab.REFERENCES;
                selectReference(value.name());
                status = "Tracking String value as " + value.name();
            }
        });
    }

    private static RemoteField findStringField(RemoteClass type, String expression) {
        int qualifier = expression.lastIndexOf("::");
        String owner = qualifier < 0 ? null : expression.substring(0, qualifier);
        String name = qualifier < 0 ? expression : expression.substring(qualifier + 2);
        List<RemoteField> matches = new ArrayList<RemoteField>();
        for (RemoteField field : type.getStaticFields()) {
            if (stringFieldMatches(field, owner, name)) matches.add(field);
        }
        for (RemoteField field : type.getVirtualFields()) {
            if (stringFieldMatches(field, owner, name)) matches.add(field);
        }
        if (matches.size() != 1) throw new IllegalArgumentException(matches.isEmpty()
                ? "String field was not found: " + type.className() + "." + expression
                : "String field is ambiguous; use declaring.Class::field");
        return matches.get(0);
    }

    private static boolean stringFieldMatches(RemoteField field, String owner, String name) {
        return field.name().equals(name) && "Ljava/lang/String;".equals(field.descriptor())
                && (owner == null || owner.equals(field.declaringClass()));
    }

    private void setSelectedField() throws IOException {
        if (unloadedContextClass != null) {
            status = "An unloaded class has no runtime field storage. Use U/W for pending watches, "
                    + "or l (initialize) / L (load without <clinit>) before reading/writing values.";
            return;
        }
        final List<RemoteField> visible = visibleFields();
        if (visible.isEmpty()) { status = "No field is selected."; return; }
        final RemoteField field = visible.get(selection());
        if (!field.isStatic() && session.context().isClass()) {
            status = "An object context is required to set this instance field.";
            return;
        }
        final RemoteObject receiver = field.isStatic() ? null : session.context().remoteObject();
        final String expression = editText("Set " + field.declaringClass() + "."
                + field.name() + " = (literal, @reference, or {expression})", "");
        if (expression == null) return;
        submit("Writing " + field.declaringClass() + "." + field.name() + "...",
                new Callable<String>() {
                    @Override public String call() {
                        try (RemoteArgumentList values = RemoteArgumentList.resolve(
                                session, Collections.singletonList(expression))) {
                            session.operations().write(field, receiver, values.only());
                        }
                        return field.declaringClass() + "." + field.name();
                    }
                }, new Consumer<String>() {
                    @Override public void accept(String value) {
                        status = "Updated " + value;
                        requestContextRefresh();
                    }
                });
    }

    private void setCurrentContext() throws IOException {
        if (unloadedContextClass != null) {
            status = "Offline class context is metadata-only; L loads it before runtime value writes.";
            return;
        }
        if (!session.context().isSet() || !session.context().canAssign()) {
            status = "Current context is a read-only snapshot; select a field, array element, or paused local first.";
            return;
        }
        final String source = session.context().assignmentDescription();
        final String expression = editText("Set context source " + source + " =", "");
        if (expression == null) return;
        submit("Updating " + source + "...", new Callable<String>() {
            @Override public String call() {
                try (RemoteArgumentList values = RemoteArgumentList.resolve(
                        session, Collections.singletonList(expression))) {
                    session.context().assign(values.only());
                    values.transferOnly();
                }
                return source;
            }
        }, new Consumer<String>() {
            @Override public void accept(String value) {
                status = "Updated " + value;
                requestContextRefresh();
                requestDebuggerRefresh();
            }
        });
    }

    private void setSelectedLocal() throws IOException {
        if (debuggerState == null || !debuggerState.paused() || debuggerLocals.isEmpty()) {
            status = "Writing locals requires a currently paused debugger thread (live samples are read-only).";
            return;
        }
        final JvmDebuggerLocal local = debuggerLocals.get(clamp(selection(), 0,
                debuggerLocals.size() - 1));
        if (local.descriptor() == null || local.descriptor().isEmpty()
                || "?".equals(local.descriptor())) {
            status = "This inferred slot has no reliable descriptor and cannot be written safely.";
            return;
        }
        final long sequence = debuggerState.sequence();
        final int depth = debuggerFrameDepth;
        final int slot = local.slot();
        final String descriptor = local.descriptor();
        final String expression = editText("Set local " + local.name() + " [slot " + slot + "] =", "");
        if (expression == null) return;
        submit("Writing local slot " + slot + "...", new Callable<String>() {
            @Override public String call() {
                try (RemoteArgumentList values = RemoteArgumentList.resolve(
                        session, Collections.singletonList(expression))) {
                    session.operations().debuggerLocalAssignment(sequence, depth, slot, descriptor)
                            .write(values.only());
                }
                return local.name();
            }
        }, new Consumer<String>() {
            @Override public void accept(String value) {
                status = "Updated local " + value + " in frame #" + depth;
                requestDebuggerRefresh();
            }
        });
    }

    private void invokeSelectedMethod(final boolean exactDispatch) throws IOException {
        if (unloadedContextClass != null) {
            status = "The selected class is still unloaded. Use B/S/F9 for offline debugging, "
                    + "or L to load it before invoking a method.";
            return;
        }
        final List<RemoteMethod> visible = visibleMethods();
        if (visible.isEmpty()) { status = "No method is selected."; return; }
        final RemoteMethod method = visible.get(selection());
        if (method.isJvmSpecial()) {
            status = method.name() + " is a lifecycle method; use construction/Class.forName or redefine.";
            return;
        }
        if (!method.isStatic() && session.context().isClass()) {
            status = "An object context is required to invoke this instance method.";
            return;
        }
        final RemoteObject receiver = method.isStatic() ? null : session.context().remoteObject();
        final List<String> expressions = new ArrayList<String>();
        List<String> parameterTypes = method.parameterTypeNames();
        for (int index = 0; index < parameterTypes.size(); index++) {
            String value = editText("Argument " + index + " (" + parameterTypes.get(index)
                    + "): literal, @reference, or {expression}", "");
            if (value == null) { status = "Invocation cancelled."; return; }
            expressions.add(value);
        }
        submit("Invoking " + method.declaringClass() + "." + method.name() + "...",
                new Callable<RemoteObject>() {
                    @Override public RemoteObject call() {
                        try (RemoteArgumentList values = RemoteArgumentList.resolve(session, expressions)) {
                            return session.operations().invoke(
                                    method, receiver, exactDispatch, values.values());
                        }
                    }
                }, new Consumer<RemoteObject>() {
                    @Override public void accept(RemoteObject value) {
                        if ("void".equals(value.className())) {
                            value.close();
                            status = "Invocation completed: void (context unchanged)";
                        } else {
                            session.context().select(value);
                            tab = Tab.CONTEXT;
                            status = "Context <- invocation result of " + method.name();
                            requestContextRefresh();
                        }
                    }
                });
    }

    private void requestSource(boolean wholeClass) {
        if (!wholeClass && (tab == Tab.BYTECODE || tab == Tab.DEBUG)
                && bytecode != null && !bytecode.instructions().isEmpty()) {
            pendingSourceBci = bytecode.instructions().get(bytecodeCursor()).offset();
        }
        if (tab == Tab.BROWSE && !visibleBrowserEntries.isEmpty()) {
            TuiBrowserEntry entry = visibleBrowserEntries.get(selection());
            if (entry.unloaded() && entry.unloadedClass() != null) {
                if (wholeClass || entry.kind() == TuiBrowserEntry.Kind.CLASS) {
                    startDecompileBytes(entry.unloadedClass(), "", "");
                } else if (entry.kind() == TuiBrowserEntry.Kind.METHOD) {
                    startDecompileBytes(entry.unloadedClass(), entry.unloadedMember().name(),
                            entry.unloadedMember().descriptor());
                } else status = "Select an unloaded class or method to decompile.";
                return;
            }
        }
        if (tab == Tab.SOURCE && sourceCatalogClass != null) {
            startDecompileBytes(sourceCatalogClass, wholeClass ? "" : sourceMethod,
                    wholeClass ? "" : sourceDescriptor);
            return;
        }
        if ((tab == Tab.BYTECODE || tab == Tab.DEBUG) && bytecodeCatalogClass != null) {
            startDecompileBytes(bytecodeCatalogClass, wholeClass ? "" : bytecodeMethod,
                    wholeClass ? "" : bytecodeDescriptor);
            return;
        }
        if (unloadedContextClass != null
                && (tab == Tab.CONTEXT || tab == Tab.FIELDS || tab == Tab.METHODS)) {
            JvmClassPathCatalog.Member member = tab == Tab.METHODS ? selectedUnloadedMethod() : null;
            startDecompileBytes(unloadedContextClass,
                    wholeClass || member == null ? "" : member.name(),
                    wholeClass || member == null ? "" : member.descriptor());
            return;
        }
        final RemoteClass type;
        final String methodName;
        final String descriptor;
        if (wholeClass) {
            if (tab == Tab.SOURCE && !sourceClass.isEmpty()) type = session.findClass(sourceClass);
            else if ((tab == Tab.BYTECODE || tab == Tab.DEBUG) && !bytecodeClass.isEmpty()) {
                type = session.findClass(bytecodeClass);
            } else {
                if (!requireContext()) return;
                type = contextClass;
            }
            methodName = "";
            descriptor = "";
        } else if ((tab == Tab.FRAMES || tab == Tab.LOCALS) && viewedOrSelectedDebuggerFrame() != null) {
            JvmStackFrame frame = viewedOrSelectedDebuggerFrame();
            if (frame == null || !frame.hasJavaLocation()) {
                status = "The selected native frame has no Java bytecode to decompile.";
                return;
            }
            selectDebuggerFrame(frame);
            type = session.findClass(frame.className());
            methodName = frame.methodName();
            descriptor = frame.descriptor();
        } else if (((tab == Tab.BYTECODE || tab == Tab.DEBUG) && !bytecodeClass.isEmpty())
                || (tab == Tab.SOURCE && !sourceMethod.isEmpty())) {
            boolean sourceSelection = tab == Tab.SOURCE;
            type = session.findClass(sourceSelection ? sourceClass : bytecodeClass);
            methodName = sourceSelection ? sourceMethod : bytecodeMethod;
            descriptor = sourceSelection ? sourceDescriptor : bytecodeDescriptor;
        } else {
            if (!requireContext()) return;
            RemoteMethod method = selectedMethodForAction();
            if (method == null) {
                status = "Select a method in Methods, Bytecode, or Debug before decompiling.";
                tab = Tab.METHODS;
                return;
            }
            type = session.findClass(method.declaringClass());
            methodName = method.name();
            descriptor = method.descriptor();
        }
        startDecompile(type, methodName, descriptor);
    }

    /** Decompiles the bytecode interval selected by BCI rather than the complete method. */
    private void requestSourceRange() throws IOException {
        BytecodeInstruction selected = selectedBytecodeForEdit();
        if (selected == null) return;
        String entered = editText("Decompile bytecode range (start..end, or end from current BCI)",
                selected.offset() + ".." + selected.offset());
        if (entered == null || entered.trim().isEmpty()) return;
        String value = entered.trim();
        int from = selected.offset();
        int to;
        int separator = value.indexOf("..");
        try {
            if (separator >= 0) {
                from = Integer.decode(value.substring(0, separator).trim()).intValue();
                to = Integer.decode(value.substring(separator + 2).trim()).intValue();
            } else to = Integer.decode(value).intValue();
        } catch (NumberFormatException failure) {
            status = "Invalid BCI range: " + value + " (expected start..end)";
            return;
        }
        if (from < 0 || to < from) { status = "Invalid BCI range " + from + ".." + to; return; }
        final int rangeStart = from;
        final int rangeEnd = to;
        final String className = bytecodeClass;
        final String methodName = bytecodeMethod;
        final String descriptor = bytecodeDescriptor;
        final JvmClassPathCatalog.ClassEntry catalogEntry = bytecodeCatalogClass;
        final DecompilerEngine selectedEngine = engine;
        pendingSourceBci = rangeStart;
        if (!submit("Decompiling " + className + "." + methodName + " BCI "
                        + rangeStart + ".." + rangeEnd + "...",
                new Callable<DecompilationResult>() {
                    @Override public DecompilationResult call() throws IOException {
                        byte[] bytes = catalogEntry == null
                                ? session.instrumentation().bytecode().classBytes(className)
                                : catalogEntry.bytes();
                        return new ClassDecompiler().decompileRangeResult(className, bytes,
                                methodName, descriptor, rangeStart, rangeEnd, selectedEngine);
                    }
                }, new Consumer<DecompilationResult>() {
                    @Override public void accept(DecompilationResult result) {
                        sourceLines.clear();
                        addLines(sourceLines, result.source());
                        sourceClass = className;
                        sourceMethod = methodName;
                        sourceDescriptor = descriptor;
                        sourceCatalogClass = catalogEntry;
                        sourceBciToLine.clear();
                        sourceBciToLine.putAll(result.lineMappings(methodName, descriptor));
                        alignPendingSourceBci();
                        status = "Decompiled BCI " + rangeStart + ".." + rangeEnd
                                + " with CFR bytecode-location mapping";
                    }
                })) return;
        sourceLines.clear();
        sourceLines.add("Decompiling BCI " + rangeStart + ".." + rangeEnd + "...");
        sourceTitle = className + "." + methodName + descriptor
                + " [BCI " + rangeStart + ".." + rangeEnd + "]";
        tab = Tab.SOURCE;
        selections[Tab.SOURCE.ordinal()] = 0;
        scrolls[Tab.SOURCE.ordinal()] = 0;
    }

    /** Decompiles the class owning the selected browser row without requiring a context first. */
    private void requestBrowserClassSource() {
        if (visibleBrowserEntries.isEmpty()) {
            status = "Select a class, field, or method row before decompiling its class.";
            return;
        }
        TuiBrowserEntry entry = visibleBrowserEntries.get(selection());
        if (entry.kind() == TuiBrowserEntry.Kind.PARENT
                || entry.kind() == TuiBrowserEntry.Kind.PACKAGE) {
            status = "Class decompile needs a class row; Enter opens this package first.";
            return;
        }
        if (entry.unloaded() && entry.unloadedClass() != null) {
            startDecompileBytes(entry.unloadedClass(), "", "");
            return;
        }
        startDecompile(session.findClass(entry.ownerName()), "", "");
    }

    private void startDecompileBytes(final JvmClassPathCatalog.ClassEntry type,
            final String methodName, final String descriptor) {
        final DecompilerEngine selectedEngine = engine;
        final String title = methodName.isEmpty() ? type.name()
                : type.name() + "." + methodName + descriptor;
        if (!submit("Decompiling unloaded " + title + " with " + selectedEngine + "...",
                new Callable<DecompilationResult>() {
                    @Override public DecompilationResult call() throws IOException {
                        byte[] bytes = type.bytes();
                        ClassDecompiler decompiler = new ClassDecompiler();
                        return methodName.isEmpty()
                                ? decompiler.decompile(type.name(), bytes, selectedEngine)
                                : decompiler.decompileMethodResult(
                                        type.name(), bytes, methodName, descriptor, selectedEngine);
                    }
                }, new Consumer<DecompilationResult>() {
                    @Override public void accept(DecompilationResult result) {
                        sourceLines.clear();
                        addLines(sourceLines, result.source());
                        sourceClass = type.name();
                        sourceMethod = methodName;
                        sourceDescriptor = descriptor;
                        sourceCatalogClass = type;
                        sourceBciToLine.clear();
                        if (!methodName.isEmpty()) {
                            sourceBciToLine.putAll(result.lineMappings(methodName, descriptor));
                        }
                        alignPendingSourceBci();
                        status = "Decompiled unloaded " + title + " with " + selectedEngine
                                + "; no class was defined or initialized";
                    }
                })) return;
        sourceLines.clear();
        sourceLines.add("Decompiling unloaded " + title + " with " + selectedEngine + "...");
        sourceTitle = "[UNLOADED] " + title;
        sourceClass = type.name();
        sourceMethod = methodName;
        sourceDescriptor = descriptor;
        sourceCatalogClass = type;
        sourceBciToLine.clear();
        tab = Tab.SOURCE;
        selections[Tab.SOURCE.ordinal()] = 0;
        scrolls[Tab.SOURCE.ordinal()] = 0;
    }

    private void startDecompile(final RemoteClass type, final String methodName,
            final String descriptor) {
        final DecompilerEngine selectedEngine = engine;
        final String title = methodName.isEmpty() ? type.className()
                : type.className() + "." + methodName + descriptor;
        if (!submit("Decompiling " + title + " with " + selectedEngine + "...",
                new Callable<DecompilationResult>() {
                    @Override public DecompilationResult call() {
                        byte[] bytes = session.instrumentation().bytecode().classBytes(type.className());
                        ClassDecompiler decompiler = new ClassDecompiler();
                        return methodName.isEmpty()
                                ? decompiler.decompile(type.className(), bytes, selectedEngine)
                                : decompiler.decompileMethodResult(type.className(), bytes,
                                        methodName, descriptor, selectedEngine);
                    }
                }, new Consumer<DecompilationResult>() {
                    @Override public void accept(DecompilationResult result) {
                        sourceLines.clear();
                        addLines(sourceLines, result.source());
                        sourceClass = type.className();
                        sourceMethod = methodName;
                        sourceDescriptor = descriptor;
                        sourceCatalogClass = null;
                        sourceBciToLine.clear();
                        if (!methodName.isEmpty()) {
                            sourceBciToLine.putAll(result.lineMappings(methodName, descriptor));
                        }
                        if (!alignPendingSourceBci()) alignSourceWithDebugger();
                        status = "Decompiled " + title + " with " + selectedEngine
                                + (methodName.isEmpty() || !sourceBciToLine.isEmpty() ? ""
                                : "; decompiled-line breakpoints need CFR line mappings");
                    }
                })) return;
        sourceLines.clear();
        sourceLines.add("Decompiling " + title + " with " + selectedEngine + "...");
        sourceTitle = title;
        sourceCatalogClass = null;
        tab = Tab.SOURCE;
        selections[Tab.SOURCE.ordinal()] = 0;
        scrolls[Tab.SOURCE.ordinal()] = 0;
        horizontalOffsets[Tab.SOURCE.ordinal()] = 0;
    }

    private void alignSourceWithDebugger() {
        int line = sourceExecutionLine();
        if (line < 0) return;
        selections[Tab.SOURCE.ordinal()] = clamp(line,
                0, Math.max(0, sourceLines.size() - 1));
        scrolls[Tab.SOURCE.ordinal()] = Math.max(0,
                line - Math.max(1, screen.height() / 3));
    }

    private boolean alignPendingSourceBci() {
        if (pendingSourceBci < 0 || sourceBciToLine.isEmpty()) return false;
        int requested = pendingSourceBci;
        Map.Entry<Integer, Integer> mapping = sourceBciToLine.floorEntry(requested);
        if (mapping == null) mapping = sourceBciToLine.ceilingEntry(requested);
        pendingSourceBci = -1;
        if (mapping == null) return false;
        int line = mapping.getValue().intValue() - 1;
        selections[Tab.SOURCE.ordinal()] = clamp(line,
                0, Math.max(0, sourceLines.size() - 1));
        scrolls[Tab.SOURCE.ordinal()] = Math.max(0,
                line - Math.max(1, screen.height() / 3));
        return true;
    }

    /** Returns the 0-based decompiled source row for the currently viewed stack frame. */
    private int sourceExecutionLine() {
        long location = executionLocationForMethod(
                sourceClass, sourceMethod, sourceDescriptor);
        if (location == Long.MIN_VALUE || location < 0 || sourceBciToLine.isEmpty()) return -1;
        Map.Entry<Integer, Integer> mapping = sourceBciToLine.floorEntry((int) location);
        if (mapping == null) mapping = sourceBciToLine.ceilingEntry((int) location);
        return mapping == null ? -1 : mapping.getValue() - 1;
    }

    private long executionLocationForMethod(
            String className, String methodName, String descriptor) {
        if (className == null || methodName == null || descriptor == null
                || methodName.isEmpty()) return Long.MIN_VALUE;
        JvmStackFrame frame = executionFrameForMethod(className, methodName, descriptor);
        if (frame != null) return frame.location();
        if (debuggerState != null && debuggerState.paused()
                && className.equals(debuggerState.className())
                && methodName.equals(debuggerState.methodName())
                && descriptor.equals(debuggerState.descriptor())) {
            return debuggerState.location();
        }
        return Long.MIN_VALUE;
    }

    private JvmStackFrame executionFrameForMethod(
            String className, String methodName, String descriptor) {
        boolean readable = debuggerState != null && debuggerState.paused() || liveSampleAvailable;
        if (!readable) return null;
        JvmStackFrame viewed = viewedDebuggerFrame();
        if (matchesFrameMethod(viewed, className, methodName, descriptor)) return viewed;
        // A method can appear more than once through recursion. Prefer the explicitly
        // selected frame above; otherwise use the first (youngest) matching frame.
        for (JvmStackFrame frame : debuggerFrames) {
            if (matchesFrameMethod(frame, className, methodName, descriptor)) return frame;
        }
        return null;
    }

    private static boolean matchesFrameMethod(JvmStackFrame frame,
            String className, String methodName, String descriptor) {
        return frame != null && className.equals(frame.className())
                && methodName.equals(frame.methodName())
                && descriptor.equals(frame.descriptor());
    }

    private void requestSelectedBytecode(Tab destination) {
        if (tab == Tab.BROWSE && !visibleBrowserEntries.isEmpty()) {
            TuiBrowserEntry entry = visibleBrowserEntries.get(selection());
            if (entry.unloaded() && entry.kind() == TuiBrowserEntry.Kind.METHOD) {
                requestUnloadedBytecode(entry, destination);
                return;
            }
        }
        if (unloadedContextClass != null && tab == Tab.METHODS) {
            JvmClassPathCatalog.Member member = selectedUnloadedMethod();
            if (member == null) { status = "Select an unloaded method first."; return; }
            requestUnloadedBytecode(TuiBrowserEntry.unloadedMember(unloadedContextClass, member), destination);
            return;
        }
        if (!requireContext()) return;
        RemoteMethod method = selectedMethodForAction();
        if (method == null) {
            status = "Select a method first; bytecode belongs to a method, not to an object value.";
            tab = Tab.METHODS;
            return;
        }
        selectedMethod = method;
        requestBytecode(method.declaringClass(), method.name(), method.descriptor(), destination);
    }

    private void requestUnloadedBytecode(final TuiBrowserEntry entry, final Tab destination) {
        final JvmClassPathCatalog.ClassEntry owner = entry.unloadedClass();
        final JvmClassPathCatalog.Member member = entry.unloadedMember();
        if (owner == null || member == null
                || member.kind() != JvmClassPathCatalog.MemberKind.METHOD) return;
        final String className = owner.name();
        final String methodName = member.name();
        final String descriptor = member.descriptor();
        if (!submit("Reading unloaded bytecode " + className + "." + methodName + "...",
                new Callable<ClassFileView>() {
                    @Override public ClassFileView call() throws IOException {
                        return new JvmClassFileParser().parse(owner.bytes());
                    }
                }, new Consumer<ClassFileView>() {
                    @Override public void accept(ClassFileView view) {
                        bytecode = view.method(methodName, descriptor);
                        constantPool.clear();
                        constantPool.addAll(view.constants());
                        status = bytecode.instructions().isEmpty()
                                ? member.isNative() || member.isAbstract()
                                        ? "Unloaded " + (member.isNative() ? "native" : "abstract")
                                                + " method: use Ctrl+E/Ctrl+X event breakpoints"
                                        : "No Code attribute in unloaded method"
                                : "Loaded " + bytecode.instructions().size()
                                        + " instruction(s) without loading " + className
                                        + "; F9 creates a pending ClassPrepare breakpoint";
                    }
                })) return;
        bytecode = null;
        bytecodeClass = className;
        bytecodeMethod = methodName;
        bytecodeDescriptor = descriptor;
        bytecodeCatalogClass = owner;
        constantPool.clear();
        debugSearchResults.clear();
        tab = destination;
        selections[destination.ordinal()] = 0;
        scrolls[destination.ordinal()] = 0;
    }

    private void requestBytecode(final String className, final String methodName,
            final String descriptor, final Tab destination) {
        if (!submit("Loading bytecode " + className + "." + methodName + "...",
                new Callable<BytecodeLoadResult>() {
                    @Override public BytecodeLoadResult call() throws IOException {
                        // Reflection lists inherited members. Class bytes must come from the method's
                        // declaring class, not from the current context/runtime class.
                        try {
                            session.findClass(className); // Preserve the unloaded-class fallback below.
                            byte[] bytes = session.instrumentation().bytecode().classBytes(className);
                            return new BytecodeLoadResult(new JvmClassFileParser().parse(bytes), null);
                        } catch (RuntimeException failure) {
                            if (!classNotLoaded(failure)) throw failure;
                            JvmClassPathCatalog catalog = session.refreshClassPathCatalog();
                            JvmClassPathCatalog.ClassEntry entry = catalog.find(className);
                            if (entry == null) throw failure;
                            return new BytecodeLoadResult(
                                    new JvmClassFileParser().parse(entry.bytes()), entry);
                        }
                    }
                }, new Consumer<BytecodeLoadResult>() {
                    @Override public void accept(BytecodeLoadResult loaded) {
                        ClassFileView view = loaded.view;
                        bytecode = view.method(methodName, descriptor);
                        bytecodeCatalogClass = loaded.catalogEntry;
                        constantPool.addAll(view.constants());
                        alignDebuggerLocation(destination);
                        if (pendingBytecodeLocation >= 0) {
                            alignBytecodeLocation(destination, pendingBytecodeLocation);
                            pendingBytecodeLocation = -1;
                        }
                        status = bytecode.instructions().isEmpty()
                                ? "No Code attribute (native or abstract method): " + className + "." + methodName
                                : "Loaded " + bytecode.instructions().size() + " bytecode instruction(s) from " + className
                                        + (loaded.catalogEntry == null ? ""
                                                : " without loading it; breakpoint installation remains pending");
                        if (destination == Tab.DEBUG && debuggerState != null && debuggerState.paused()
                                && className.equals(debuggerState.className())
                                && methodName.equals(debuggerState.methodName())
                                && descriptor.equals(debuggerState.descriptor())) {
                            status = "STOP HIT: " + debuggerState.reason() + " | "
                                    + className + "." + methodName + " @BCI "
                                    + debuggerState.location() + " | F7 step, F8 continue";
                        }
                    }
                })) return;
        bytecode = null;
        bytecodeClass = className;
        bytecodeMethod = methodName;
        bytecodeDescriptor = descriptor;
        bytecodeCatalogClass = null;
        constantPool.clear();
        debugSearchResults.clear();
        tab = destination;
        selections[destination.ordinal()] = 0;
        scrolls[destination.ordinal()] = 0;
        horizontalOffsets[destination.ordinal()] = 0;
    }

    private void alignDebuggerLocation(Tab destination) {
        if (bytecode == null) return;
        long location = executionLocationForMethod(
                bytecodeClass, bytecodeMethod, bytecodeDescriptor);
        if (location >= 0) alignBytecodeLocation(destination, location);
    }

    private void alignBytecodeLocation(Tab destination, long location) {
        if (bytecode == null) return;
        List<BytecodeInstruction> instructions = bytecode.instructions();
        int closest = 0;
        long distance = Long.MAX_VALUE;
        for (int index = 0; index < instructions.size(); index++) {
            long candidate = Math.abs(instructions.get(index).offset() - location);
            if (candidate < distance) { distance = candidate; closest = index; }
        }
        selections[destination.ordinal()] = closest;
        scrolls[destination.ordinal()] = Math.max(0, closest - Math.max(1, screen.height() / 3));
    }

    private RemoteMethod selectedMethodForAction() {
        if (tab == Tab.METHODS) {
            List<RemoteMethod> visible = visibleMethods();
            if (!visible.isEmpty()) selectedMethod = visible.get(selection());
        }
        return selectedMethod;
    }

    private void requestDebuggerRefresh() {
        final boolean followLocation = tab == Tab.DEBUG;
        tasks.submit("", new Callable<DebuggerSnapshot>() {
            @Override public DebuggerSnapshot call() { return debuggerSnapshot(); }
        }, new Consumer<DebuggerSnapshot>() {
            @Override public void accept(DebuggerSnapshot value) {
                lastDebuggerFullRefreshAt = System.currentTimeMillis();
                applyDebuggerSnapshot(value, followLocation);
            }
        }, new Consumer<Throwable>() {
            @Override public void accept(Throwable failure) { recordError(failure); }
        });
    }

    private void maybeAutoRefreshDebugger() {
        if (tasks.busy()) return;
        long now = System.currentTimeMillis();
        if (tab == Tab.DEBUG && liveFollowEnabled && !session.debugger().active()
                && debuggerState != null && debuggerState.enabled() && !debuggerState.paused()
                && !followedThreadName.isEmpty() && now - lastLiveSampleAt >= 350L) {
            lastLiveSampleAt = now;
            tasks.submit("", new Callable<LiveExecutionSample>() {
                @Override public LiveExecutionSample call() { return captureLiveExecutionSample(); }
            }, new Consumer<LiveExecutionSample>() {
                @Override public void accept(LiveExecutionSample sample) {
                    applyLiveExecutionSample(sample);
                }
            }, new Consumer<Throwable>() {
                @Override public void accept(Throwable failure) {
                    liveSampleError = rootMessage(failure);
                }
            });
            return;
        }
        if (now - lastDebuggerPollAt < 200L) return;
        lastDebuggerPollAt = now;
        boolean debuggerPage = tab == Tab.DEBUG || tab == Tab.FRAMES
                || tab == Tab.LOCALS || tab == Tab.THREADS;
        boolean needsFullRefresh = debuggerState == null
                || (tab == Tab.THREADS && now - lastDebuggerFullRefreshAt >= 1200L);
        if (debuggerPage && needsFullRefresh) {
            final long observed = lastObservedStopSequence;
            lastDebuggerFullRefreshAt = now;
            tasks.submit("", new Callable<DebuggerSnapshot>() {
                @Override public DebuggerSnapshot call() { return debuggerSnapshot(observed); }
            }, new Consumer<DebuggerSnapshot>() {
                @Override public void accept(DebuggerSnapshot value) {
                    boolean newStop = newestPausedSequence(value.states) > lastObservedStopSequence;
                    applyDebuggerSnapshot(value, newStop && tab == Tab.DEBUG);
                    if (newStop && tab == Tab.THREADS) {
                        status = "STOP HIT: marked with >> in Threads; Enter or G opens current bytecode";
                    }
                }
            }, new Consumer<Throwable>() {
                @Override public void accept(Throwable failure) { }
            });
            return;
        }
        // A fast sequence-only probe detects a new stop without repeatedly re-reading
        // stack/locals or disturbing the current tab/cursor. The full stop transaction
        // is requested only when the sequence changes.
        tasks.submit("", new Callable<List<JvmDebuggerState>>() {
            @Override public List<JvmDebuggerState> call() {
                return new ArrayList<JvmDebuggerState>(session.jvmti().debuggerStates());
            }
        }, new Consumer<List<JvmDebuggerState>>() {
            @Override public void accept(List<JvmDebuggerState> states) {
                long newest = newestPausedSequence(states);
                boolean selectedStopDisappeared = debuggerState != null && debuggerState.paused()
                        && !containsPausedSequence(states, activeDebuggerSequence);
                for (JvmDebuggerState state : states) state.close();
                if (newest > lastObservedStopSequence) requestDetectedStop(lastObservedStopSequence);
                else if (selectedStopDisappeared && debuggerPage) requestDebuggerRefresh();
            }
        }, new Consumer<Throwable>() {
            @Override public void accept(Throwable failure) { }
        });
    }

    private LiveExecutionSample captureLiveExecutionSample() {
        final long previouslyObserved = lastObservedStopSequence;
        List<RemoteJvmtiThread> threads = new ArrayList<RemoteJvmtiThread>(session.jvmti().threads());
        RemoteJvmtiThread selected = null;
        boolean pauseRequested = false;
        try {
            for (RemoteJvmtiThread thread : threads) {
                if (followedThreadName.equals(thread.name())) { selected = thread; break; }
            }
            if (selected == null) {
                return LiveExecutionSample.error(followedThreadName,
                        "followed thread is no longer alive");
            }
            if (selected.debuggerPaused()) {
                return LiveExecutionSample.realStop(previouslyObserved);
            }
            session.jvmti().configureDebugger(true);
            session.jvmti().pauseExecution(selected.object(), "live_sample");
            pauseRequested = true;
            DebuggerSnapshot snapshot = debuggerSnapshot(previouslyObserved);
            JvmDebuggerState sampleState = null;
            boolean realStopDetected = false;
            for (JvmDebuggerState state : snapshot.states) {
                if (!state.paused() || state.thread() == null) continue;
                if (state.sequence() > previouslyObserved && !"live_sample".equals(state.reason())) {
                    realStopDetected = true;
                }
                if ("live_sample".equals(state.reason())
                        && followedThreadName.equals(threadNameForState(state, threads))) {
                    sampleState = state;
                }
            }
            if (sampleState == null) {
                snapshot.close();
                pauseRequested = false; // An already-existing real stop was not created by us.
                return LiveExecutionSample.realStop(previouslyObserved);
            }
            session.jvmti().continueExecution(sampleState.thread());
            pauseRequested = false;
            if (realStopDetected) {
                snapshot.close();
                return LiveExecutionSample.realStop(previouslyObserved);
            }
            return LiveExecutionSample.captured(followedThreadName, snapshot);
        } finally {
            if (pauseRequested && selected != null) {
                try { session.jvmti().continueExecution(selected.object()); }
                catch (RuntimeException ignored) { }
            }
            for (RemoteJvmtiThread thread : threads) thread.close();
        }
    }

    private void applyLiveExecutionSample(LiveExecutionSample sample) {
        if (sample == null) return;
        if (sample.realStopDetected) {
            requestDetectedStop(sample.previouslyObserved);
            return;
        }
        if (sample.snapshot == null) {
            liveSampleError = sample.error;
            return;
        }
        DebuggerSnapshot value = sample.snapshot;
        JvmDebuggerState captured = value.selectedState();
        if (captured == null || !captured.paused() || !"live_sample".equals(captured.reason())) {
            value.close();
            return;
        }
        lastObservedStopSequence = Math.max(lastObservedStopSequence, captured.sequence());
        for (JvmDebuggerLocal local : debuggerLocals) local.close();
        debuggerLocals.clear();
        debuggerStack.clear();
        debuggerStack.addAll(value.stack);
        debuggerFrames.clear();
        debuggerFrames.addAll(value.frames);
        debuggerFrameDepth = value.frameDepth;
        debuggerLocals.addAll(value.locals);
        value.locals.clear(); // Ownership moves to the live-sample view/context stack.
        debuggerLocalsError = value.localsError;
        selections[Tab.FRAMES.ordinal()] = clamp(debuggerFrameDepth, 0,
                Math.max(0, debuggerFrames.size() - 1));
        selections[Tab.LOCALS.ordinal()] = clamp(selections[Tab.LOCALS.ordinal()], 0,
                Math.max(0, debuggerLocals.size() - 1));

        JvmStackFrame viewed = viewedDebuggerFrame();
        liveSampleActual = captured.className() + "." + captured.methodName()
                + captured.descriptor() + " @" + captured.location();
        liveSampleView = viewed == null ? "<no Java frame>" : viewed.className() + "."
                + viewed.methodName() + viewed.descriptor() + " @" + viewed.location()
                + " frame#" + viewed.depth();
        liveSampleCapturedAt = System.currentTimeMillis();
        liveSampleAvailable = true;
        liveSampleError = "";

        boolean methodMatches = viewed != null && bytecode != null
                && bytecodeClass.equals(viewed.className())
                && bytecodeMethod.equals(viewed.methodName())
                && bytecodeDescriptor.equals(viewed.descriptor());
        if (value.stopBytecodeView != null && viewed != null) {
            bytecode = value.stopBytecodeView.method(value.stopBytecodeMethod,
                    value.stopBytecodeDescriptor);
            bytecodeClass = value.stopBytecodeClass;
            bytecodeMethod = value.stopBytecodeMethod;
            bytecodeDescriptor = value.stopBytecodeDescriptor;
            constantPool.clear();
            constantPool.addAll(value.stopBytecodeView.constants());
            alignBytecodeLocation(Tab.DEBUG, value.stopBytecodeLocation);
        // In the same method the live execution marker moves independently. Keep
        // the user's cursor stable until G explicitly recentres the current BCI.
        } else if (!methodMatches && viewed != null && viewed.hasJavaLocation()) {
            pendingBytecodeLocation = (int) viewed.location();
            requestBytecode(viewed.className(), viewed.methodName(), viewed.descriptor(), Tab.DEBUG);
        }
        status = "LIVE FOLLOW " + sample.threadName + " | " + liveSampleView;
        value.close();
    }

    private void toggleLiveFollow() {
        liveFollowEnabled = !liveFollowEnabled;
        lastLiveSampleAt = 0L;
        status = liveFollowEnabled
                ? "Live follow enabled; RUNNING threads are sampled without leaving them paused"
                : "Live follow disabled; Debug keeps the last captured view";
    }

    private static boolean containsPausedSequence(List<JvmDebuggerState> states, long sequence) {
        for (JvmDebuggerState state : states) {
            if (state.paused() && state.sequence() == sequence) return true;
        }
        return false;
    }

    private void requestDetectedStop(final long previouslyObserved) {
        status = "Debugger stop detected; loading thread, locals and current bytecode...";
        submit("Opening debugger stop...", new Callable<DebuggerSnapshot>() {
            @Override public DebuggerSnapshot call() { return debuggerSnapshot(previouslyObserved); }
        }, new Consumer<DebuggerSnapshot>() {
            @Override public void accept(DebuggerSnapshot value) {
                lastDebuggerFullRefreshAt = System.currentTimeMillis();
                if (tab != Tab.THREADS) tab = Tab.DEBUG;
                applyDebuggerSnapshot(value, tab == Tab.DEBUG);
                if (tab == Tab.THREADS) {
                    status = "STOP HIT: thread marked with >>; Enter or G jumps to Debug";
                }
            }
        });
    }

    private static long newestPausedSequence(List<JvmDebuggerState> states) {
        long newest = -1L;
        for (JvmDebuggerState state : states) {
            if (state.paused()) newest = Math.max(newest, state.sequence());
        }
        return newest;
    }

    private DebuggerSnapshot debuggerSnapshot() {
        return debuggerSnapshot(-1L);
    }

    private DebuggerSnapshot debuggerSnapshot(long newerThanSequence) {
        final long knownSequence = Math.max(newerThanSequence, activeDebuggerSequence);
        List<JvmDebuggerState> states = new ArrayList<JvmDebuggerState>(session.jvmti().debuggerStates());
        List<RemoteJvmtiThread> threads = new ArrayList<RemoteJvmtiThread>(session.jvmti().threads());
        Collections.sort(threads, new Comparator<RemoteJvmtiThread>() {
            @Override public int compare(RemoteJvmtiThread left, RemoteJvmtiThread right) {
                int name = left.name().compareToIgnoreCase(right.name());
                return name != 0 ? name : left.name().compareTo(right.name());
            }
        });
        int selected = 0;
        long newestCandidate = newerThanSequence;
        if (newerThanSequence >= 0) {
            for (int index = 0; index < states.size(); index++) {
                JvmDebuggerState candidate = states.get(index);
                if (candidate.paused() && candidate.sequence() > newestCandidate) {
                    newestCandidate = candidate.sequence();
                    selected = index;
                }
            }
        }
        if (newestCandidate == newerThanSequence) {
            for (int index = 0; index < states.size(); index++) {
                JvmDebuggerState candidate = states.get(index);
                if (!followedThreadName.isEmpty() && candidate.paused() && candidate.thread() != null
                        && candidate.thread().displayValue().contains(followedThreadName)) {
                    selected = index;
                    break;
                }
                if (candidate.sequence() == activeDebuggerSequence) selected = index;
            }
        }
        JvmDebuggerState state = states.isEmpty() ? null : states.get(selected);
        List<String> stack = new ArrayList<String>();
        List<JvmStackFrame> frames = new ArrayList<JvmStackFrame>();
        List<JvmDebuggerLocal> locals = new ArrayList<JvmDebuggerLocal>();
        String localsError = "";
        int frameDepth = 0;
        ClassFileView stopBytecodeView = null;
        String stopBytecodeClass = "";
        String stopBytecodeMethod = "";
        String stopBytecodeDescriptor = "";
        long stopBytecodeLocation = -1L;
        String stopBytecodeError = "";
        if (state != null && state.paused() && state.thread() != null) {
            try {
                frames.addAll(session.jvmti().stackFrames(state.thread(), 48));
                for (JvmStackFrame frame : frames) stack.add(frame.raw());
                if ("live_sample".equals(state.reason()) && liveFollowFrameDepth >= 0) {
                    frameDepth = clamp(liveFollowFrameDepth, 0, Math.max(0, frames.size() - 1));
                } else {
                    frameDepth = state.sequence() == activeDebuggerSequence
                            ? clamp(debuggerFrameDepth, 0, Math.max(0, frames.size() - 1))
                            : preferredFrameDepth(frames);
                }
            }
            catch (RuntimeException failure) { stack.add("<stack unavailable: " + rootMessage(failure) + ">"); }
            try { locals.addAll(session.jvmti().debuggerLocals(state.thread(), frameDepth)); }
            catch (RuntimeException failure) { localsError = friendlyLocalsError(failure); }
            if (state.sequence() > knownSequence && !frames.isEmpty()) {
                JvmStackFrame frame = frames.get(clamp(frameDepth, 0, frames.size() - 1));
                boolean alreadyLoaded = bytecode != null
                        && bytecodeClass.equals(frame.className())
                        && bytecodeMethod.equals(frame.methodName())
                        && bytecodeDescriptor.equals(frame.descriptor());
                if (frame.hasJavaLocation() && !alreadyLoaded) {
                    stopBytecodeClass = frame.className();
                    stopBytecodeMethod = frame.methodName();
                    stopBytecodeDescriptor = frame.descriptor();
                    stopBytecodeLocation = frame.location();
                    try {
                        stopBytecodeView = session.findClass(stopBytecodeClass).classFileView();
                        // Validate the exact method while this stop transaction is still current.
                        stopBytecodeView.method(stopBytecodeMethod, stopBytecodeDescriptor);
                    } catch (RuntimeException failure) {
                        stopBytecodeView = null;
                        stopBytecodeError = rootMessage(failure);
                    }
                }
            }
        }
        return new DebuggerSnapshot(states, selected, threads, stack, frames,
                frameDepth, locals, localsError, stopBytecodeView, stopBytecodeClass,
                stopBytecodeMethod, stopBytecodeDescriptor, stopBytecodeLocation,
                stopBytecodeError);
    }

    private static int preferredFrameDepth(List<JvmStackFrame> frames) {
        if (frames.isEmpty() || frames.get(0).hasJavaLocation()) return 0;
        for (JvmStackFrame frame : frames) {
            if (frame.hasJavaLocation() && !frame.isPlatformFrame()) return frame.depth();
        }
        for (JvmStackFrame frame : frames) if (frame.hasJavaLocation()) return frame.depth();
        return 0;
    }

    private void applyDebuggerSnapshot(DebuggerSnapshot value, boolean loadLocation) {
        if (value.newestSequence() >= 0 && value.newestSequence() < lastObservedStopSequence) {
            value.close();
            return;
        }
        JvmDebuggerState incoming = value.selectedState();
        long previousSequence = activeDebuggerSequence;
        String previousClass = debuggerState == null ? "" : debuggerState.className();
        String previousMethod = debuggerState == null ? "" : debuggerState.methodName();
        String previousDescriptor = debuggerState == null ? "" : debuggerState.descriptor();
        long previousLocation = debuggerState == null ? -1L : debuggerState.location();
        boolean newStop = incoming != null && incoming.paused()
                && incoming.sequence() != previousSequence;
        boolean executionMoved = incoming != null && incoming.paused()
                && (!incoming.className().equals(previousClass)
                || !incoming.methodName().equals(previousMethod)
                || !incoming.descriptor().equals(previousDescriptor)
                || incoming.location() != previousLocation);
        String selectedThreadName = selectedDebuggerThread() == null
                ? followedThreadName : selectedDebuggerThread().name();
        if (newStop) {
            String stoppedThreadName = threadNameForState(incoming, value.threads);
            if (!stoppedThreadName.isEmpty()) followedThreadName = stoppedThreadName;
            liveFollowFrameDepth = -1;
        }
        if (newStop) rememberLastStop(incoming, value.stack, value.locals,
                value.localsError);
        liveSampleAvailable = false;
        closeDebuggerStates();
        debuggerStates.addAll(value.states);
        session.stringHooks().observe(debuggerStates);
        debuggerThreads.addAll(value.threads);
        if (!selectedThreadName.isEmpty()) {
            for (int index = 0; index < debuggerThreads.size(); index++) {
                if (selectedThreadName.equals(debuggerThreads.get(index).name())) {
                    selections[Tab.THREADS.ordinal()] = index;
                    break;
                }
            }
        }
        debuggerState = value.selectedState();
        activeDebuggerSequence = debuggerState == null ? -1L : debuggerState.sequence();
        lastObservedStopSequence = Math.max(lastObservedStopSequence,
                newestPausedSequence(debuggerStates));
        debuggerStack.clear();
        debuggerStack.addAll(value.stack);
        debuggerFrames.clear();
        debuggerFrames.addAll(value.frames);
        debuggerFrameDepth = value.frameDepth;
        debuggerLocals.addAll(value.locals);
        debuggerLocalsError = value.localsError;
        selections[Tab.FRAMES.ordinal()] = clamp(debuggerFrameDepth, 0,
                Math.max(0, debuggerFrames.size() - 1));
        selections[Tab.LOCALS.ordinal()] = clamp(selections[Tab.LOCALS.ordinal()], 0,
                Math.max(0, debuggerLocals.size() - 1));
        if (debuggerState != null && debuggerState.paused()) {
            status = (newStop ? "STOP HIT: " : "") + debuggerState.toString()
                    + " | " + pausedDebuggerCount() + " paused thread(s)";
            JvmStackFrame viewed = viewedDebuggerFrame();
            boolean viewedMethodMatches = viewed != null && bytecode != null
                    && bytecodeClass.equals(viewed.className())
                    && bytecodeMethod.equals(viewed.methodName())
                    && bytecodeDescriptor.equals(viewed.descriptor());
            boolean stopBytecodeApplied = false;
            if (newStop && value.stopBytecodeView != null
                    && viewed != null
                    && value.stopBytecodeClass.equals(viewed.className())
                    && value.stopBytecodeMethod.equals(viewed.methodName())
                    && value.stopBytecodeDescriptor.equals(viewed.descriptor())) {
                bytecode = value.stopBytecodeView.method(value.stopBytecodeMethod,
                        value.stopBytecodeDescriptor);
                bytecodeClass = value.stopBytecodeClass;
                bytecodeMethod = value.stopBytecodeMethod;
                bytecodeDescriptor = value.stopBytecodeDescriptor;
                constantPool.clear();
                constantPool.addAll(value.stopBytecodeView.constants());
                pendingBytecodeLocation = -1;
                if (tab == Tab.DEBUG) {
                    alignBytecodeLocation(Tab.DEBUG, value.stopBytecodeLocation);
                }
                stopBytecodeApplied = true;
            }
            if ((loadLocation || newStop) && tab == Tab.DEBUG
                    && viewed != null && viewed.hasJavaLocation() && !stopBytecodeApplied) {
                if (viewedMethodMatches) {
                    // A new BCI in the same method only moves the execution marker. Never
                    // discard/reload 900+ instructions or reset a user's cursor on a poll.
                    if (newStop || executionMoved) alignBytecodeLocation(Tab.DEBUG, viewed.location());
                } else {
                    pendingBytecodeLocation = (int) viewed.location();
                    requestBytecode(viewed.className(), viewed.methodName(),
                            viewed.descriptor(), Tab.DEBUG);
                }
            }
            if (newStop && !value.stopBytecodeError.isEmpty()) {
                status += " | bytecode load pending: " + value.stopBytecodeError;
            }
            if (newStop && debuggerState.location() < 0 && viewed != null
                    && viewed.depth() > 0 && viewed.hasJavaLocation()) {
                status += " | native top frame; viewing Java caller frame #" + viewed.depth();
            }
        } else status = debuggerState != null && debuggerState.enabled()
                ? "Target is running; " + debuggerThreads.size()
                        + " JVM thread(s), F6 pauses the selected thread"
                : "Debugger is disabled. Set a breakpoint with F9 to enable it.";
    }

    private static String threadNameForState(JvmDebuggerState state,
            List<RemoteJvmtiThread> threads) {
        if (state == null || state.thread() == null) return "";
        String display = state.thread().displayValue();
        if (display == null) return "";
        for (RemoteJvmtiThread thread : threads) {
            if (display.contains("[" + thread.name() + ",")
                    || display.contains("," + thread.name() + ",")) return thread.name();
        }
        return "";
    }

    private void rememberLastStop(JvmDebuggerState state, List<String> stack,
            List<JvmDebuggerLocal> locals, String localsError) {
        lastStopSummary = state.reason() + " at " + state.className() + "." + state.methodName()
                + " BCI " + state.location() + (state.sourceLine() < 0 ? "" : " line " + state.sourceLine());
        lastDebuggerStack.clear();
        lastDebuggerStack.addAll(stack);
        lastDebuggerLocals.clear();
        if (localsError != null && !localsError.isEmpty()) {
            lastDebuggerLocals.add("<unavailable: " + localsError + ">");
        } else if (locals.isEmpty()) {
            lastDebuggerLocals.add("<no active variables or LocalVariableTable>");
        } else {
            for (JvmDebuggerLocal local : locals) {
                lastDebuggerLocals.add((local.inferred() ? "~" : "") + "[" + local.slot()
                        + "] " + local.name() + " "
                        + local.descriptor());
                lastDebuggerLocals.add("    " + (local.available()
                        ? local.value() == null ? "null" : local.value().displayValue()
                        : "<" + local.error() + ">"));
            }
        }
    }

    private static String friendlyLocalsError(Throwable failure) {
        String message = rootMessage(failure);
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("absent_information") || lower.contains("absent information")) {
            return "This method has no LocalVariableTable; compile with debug information (-g)";
        }
        if (lower.contains("no longer paused") || lower.contains("thread_not_suspended")
                || lower.contains("thread not suspended")) {
            return "The thread resumed while locals were being read; they will refresh at the next stop";
        }
        if (lower.contains("must possess capability") || lower.contains("not available")) {
            return "can_access_local_variables is unavailable in this JVM; start with -agentpath";
        }
        int exception = message.lastIndexOf(": ");
        return exception >= 0 ? message.substring(exception + 2) : message;
    }

    private void step() {
        if (tab == Tab.THREADS) selectPausedStateForThread(selectedDebuggerThread());
        if (debuggerState == null || !debuggerState.paused()) {
            status = "F7 steps a paused thread. Select it in Threads and press F6 first.";
            tab = Tab.THREADS;
            if (!tasks.busy()) requestDebuggerRefresh();
            return;
        }
        final long sequence = debuggerState.sequence();
        final RemoteObject thread = debuggerState.thread();
        submit("Single-stepping one JVM bytecode...", new Callable<DebuggerSnapshot>() {
            @Override public DebuggerSnapshot call() throws Exception {
                session.jvmti().stepInstruction(thread);
                DebuggerSnapshot latest = null;
                for (int attempt = 0; attempt < 100; attempt++) {
                    Thread.sleep(20L);
                    if (latest != null) latest.close();
                    latest = debuggerSnapshot(sequence);
                    JvmDebuggerState selected = latest.selectedState();
                    if (selected != null && selected.paused() && selected.sequence() > sequence) return latest;
                }
                return latest;
            }
        }, new Consumer<DebuggerSnapshot>() {
            @Override public void accept(DebuggerSnapshot value) { applyDebuggerSnapshot(value, true); }
        });
    }

    private void stepOut() {
        if (tab == Tab.THREADS) selectPausedStateForThread(selectedDebuggerThread());
        if (debuggerState == null || !debuggerState.paused()) {
            status = "Shift+F7 steps out of a paused Java frame. Pause a thread first.";
            tab = Tab.THREADS;
            if (!tasks.busy()) requestDebuggerRefresh();
            return;
        }
        final long sequence = debuggerState.sequence();
        final RemoteObject thread = debuggerState.thread();
        submit("Running until the current Java frame returns...", new Callable<DebuggerSnapshot>() {
            @Override public DebuggerSnapshot call() throws Exception {
                session.jvmti().stepOut(thread);
                DebuggerSnapshot latest = null;
                for (int attempt = 0; attempt < 250; attempt++) {
                    Thread.sleep(20L);
                    if (latest != null) latest.close();
                    latest = debuggerSnapshot(sequence);
                    JvmDebuggerState selected = latest.selectedState();
                    if (selected != null && selected.paused() && selected.sequence() > sequence) return latest;
                }
                return latest;
            }
        }, new Consumer<DebuggerSnapshot>() {
            @Override public void accept(DebuggerSnapshot value) { applyDebuggerSnapshot(value, true); }
        });
    }

    private void forceEarlyReturn() throws IOException {
        if (debuggerState == null || !debuggerState.paused()) {
            status = "Ctrl+R forces a currently paused Java frame to return.";
            return;
        }
        if (debuggerState.reason().startsWith("method_exit")) {
            status = "The method has already returned; force return at entry, a BCI breakpoint, or a step stop.";
            return;
        }
        final RemoteObject thread = debuggerState.thread();
        final String descriptor = debuggerState.descriptor();
        int close = descriptor == null ? -1 : descriptor.lastIndexOf(')');
        final boolean returnsVoid = close >= 0 && close + 1 < descriptor.length()
                && descriptor.charAt(close + 1) == 'V';
        if (returnsVoid) {
            final String confirmation = editText("Force this void method to return now? Type yes", "");
            if (!"yes".equalsIgnoreCase(confirmation == null ? "" : confirmation.trim())) {
                status = confirmation == null ? status : "Force return cancelled.";
                return;
            }
            submit("Forcing the current void method to return...", new Callable<DebuggerSnapshot>() {
                @Override public DebuggerSnapshot call() {
                    session.jvmti().forceEarlyReturnVoid(thread);
                    return debuggerSnapshot();
                }
            }, new Consumer<DebuggerSnapshot>() {
                @Override public void accept(DebuggerSnapshot value) {
                    applyDebuggerSnapshot(value, true);
                    status = "Forced the selected void method to return";
                }
            });
            return;
        }
        final String expression = editText("Force return value (literal, @reference, or {expression})", "");
        if (expression == null) return;
        submit("Forcing the current Java method to return...", new Callable<DebuggerSnapshot>() {
            @Override public DebuggerSnapshot call() {
                try (RemoteArgumentList values = RemoteArgumentList.resolve(
                        session, Collections.singletonList(expression))) {
                    session.jvmti().forceEarlyReturn(thread, values.only());
                }
                return debuggerSnapshot();
            }
        }, new Consumer<DebuggerSnapshot>() {
            @Override public void accept(DebuggerSnapshot value) {
                applyDebuggerSnapshot(value, true);
                status = "Forced the selected method to return " + expression;
            }
        });
    }

    private void toggleMethodEventBreakpoint(final boolean entry) {
        final String owner;
        final String name;
        final String descriptor;
        final boolean abstractMethod;
        if (unloadedContextClass != null && tab == Tab.METHODS) {
            JvmClassPathCatalog.Member selected = selectedUnloadedMethod();
            if (selected == null) { status = "Select an unloaded method first."; return; }
            owner = unloadedContextClass.name();
            name = selected.name();
            descriptor = selected.descriptor();
            abstractMethod = selected.isAbstract();
        } else if (tab == Tab.BROWSE && !visibleBrowserEntries.isEmpty()) {
            TuiBrowserEntry selected = visibleBrowserEntries.get(selection());
            if (!selected.unloaded() || selected.kind() != TuiBrowserEntry.Kind.METHOD
                    || selected.unloadedMember() == null) {
                status = "Select an unloaded method first.";
                return;
            }
            owner = selected.ownerName();
            name = selected.unloadedMember().name();
            descriptor = selected.unloadedMember().descriptor();
            abstractMethod = selected.unloadedMember().isAbstract();
        } else {
            final RemoteMethod method = selectedMethodForAction();
            if (method == null) { status = "Select a method first."; return; }
            owner = method.declaringClass();
            name = method.name();
            descriptor = method.descriptor();
            abstractMethod = method.isAbstract();
        }
        JvmEventBreakpointSpec candidate = entry
                ? JvmEventBreakpointSpec.methodEntry(owner, name, descriptor)
                : JvmEventBreakpointSpec.methodExit(owner, name, descriptor);
        if (abstractMethod) candidate = candidate.includingSubtypes();
        final JvmEventBreakpointSpec spec = candidate;
        JvmEventBreakpointInfo found = null;
        for (JvmEventBreakpointInfo installed : session.jvmti().managedEventBreakpoints()) {
            JvmEventBreakpointSpec value = installed.spec();
            if (value.kind() == spec.kind() && value.classPattern().equals(spec.classPattern())
                    && value.methodPattern().equals(spec.methodPattern())
                    && value.descriptorPattern().equals(spec.descriptorPattern())
                    && value.includeSubtypes() == spec.includeSubtypes()) { found = installed; break; }
        }
        final JvmEventBreakpointInfo existing = found;
        submit((existing == null ? "Setting " : "Clearing ")
                + (entry ? "method-entry" : "method-exit") + " event breakpoint...",
                new Callable<Boolean>() {
                    @Override public Boolean call() {
                        if (existing == null) {
                            session.jvmti().configureDebugger(true);
                            session.jvmti().setEventBreakpoint(spec);
                            return Boolean.TRUE;
                        }
                        session.jvmti().clearEventBreakpoint(existing);
                        return Boolean.FALSE;
                    }
                }, new Consumer<Boolean>() {
                    @Override public void accept(Boolean enabled) {
                        status = (entry ? "Method-entry" : "Method-exit") + " event breakpoint "
                                + (enabled.booleanValue() ? "set" : "cleared") + " on "
                                + owner + "." + name
                                + (spec.includeSubtypes() ? " (implementations included)" : "");
                    }
                });
    }

    private void toggleExceptionBreakpoint() {
        JvmEventBreakpointInfo found = null;
        for (JvmEventBreakpointInfo installed : session.jvmti().managedEventBreakpoints()) {
            JvmEventBreakpointSpec spec = installed.spec();
            if (spec.kind() == nhcm.jvmrtdp.api.jvmti.JvmEventBreakpointKind.EXCEPTION_THROW
                    && "*".equals(spec.classPattern())) { found = installed; break; }
        }
        final JvmEventBreakpointInfo existing = found;
        submit(existing == null ? "Enabling pause on thrown exceptions..."
                : "Disabling pause on thrown exceptions...", new Callable<Boolean>() {
            @Override public Boolean call() {
                if (existing == null) {
                    session.jvmti().configureDebugger(true);
                    session.jvmti().setEventBreakpoint(JvmEventBreakpointSpec.exception("*"));
                    return Boolean.TRUE;
                }
                session.jvmti().clearEventBreakpoint(existing);
                return Boolean.FALSE;
            }
        }, new Consumer<Boolean>() {
            @Override public void accept(Boolean enabled) {
                status = enabled.booleanValue() ? "Debugger will pause when any exception is thrown"
                        : "Pause-on-exception disabled";
            }
        });
    }

    private void continueExecution() {
        if (tab == Tab.THREADS) selectPausedStateForThread(selectedDebuggerThread());
        if (debuggerState == null || !debuggerState.paused()) {
            status = "Selected thread is running. F6 pauses it; F9 sets a BCI breakpoint.";
            return;
        }
        String stoppedThreadName = threadNameForState(debuggerState, debuggerThreads);
        if (!stoppedThreadName.isEmpty()) followedThreadName = stoppedThreadName;
        lastLiveSampleAt = 0L;
        final RemoteObject thread = debuggerState.thread();
        submit("Continuing target thread...", new Callable<DebuggerSnapshot>() {
            @Override public DebuggerSnapshot call() {
                session.jvmti().continueExecution(thread);
                return debuggerSnapshot();
            }
        }, new Consumer<DebuggerSnapshot>() {
            @Override public void accept(DebuggerSnapshot value) { applyDebuggerSnapshot(value, false); }
        });
    }

    private void continueAllExecutions() {
        if (session.debugger().active()) {
            status = "Analysis freeze is active; press * to restore only freeze-owned threads.";
            return;
        }
        if (pausedDebuggerCount() == 0) {
            status = "Target is already running; there are no stopped threads to continue.";
            return;
        }
        String stoppedThreadName = threadNameForState(debuggerState, debuggerThreads);
        if (!stoppedThreadName.isEmpty()) followedThreadName = stoppedThreadName;
        lastLiveSampleAt = 0L;
        submit("Continuing all paused target threads...", new Callable<DebuggerSnapshot>() {
            @Override public DebuggerSnapshot call() {
                session.jvmti().continueAllExecutions();
                return debuggerSnapshot();
            }
        }, new Consumer<DebuggerSnapshot>() {
            @Override public void accept(DebuggerSnapshot value) { applyDebuggerSnapshot(value, false); }
        });
    }

    private void toggleAnalysisFreeze() {
        final boolean restore = session.debugger().active();
        submit(restore ? "Restoring pre-freeze thread states..."
                        : "Freezing eligible JVM threads for analysis...",
                new Callable<DebuggerFreezeReport>() {
                    @Override public DebuggerFreezeReport call() {
                        return restore ? session.debugger().restore() : session.debugger().freeze();
                    }
                }, new Consumer<DebuggerFreezeReport>() {
                    @Override public void accept(DebuggerFreezeReport report) {
                        lastFreezeReport = report;
                        status = report.summary() + (report.count(DebuggerFreezeReport.Action.FAILED) == 0
                                ? "" : "; press I for details and retry * if needed");
                        requestDebuggerRefresh();
                    }
                });
    }

    private void switchDebuggerThread() {
        if (tasks.userOperationBusy()) { status = busyMessage(); return; }
        List<JvmDebuggerState> paused = new ArrayList<JvmDebuggerState>();
        for (JvmDebuggerState state : debuggerStates) if (state.paused()) paused.add(state);
        if (paused.isEmpty()) {
            status = "Target is running; T cycles threads only when breakpoint events are simultaneously paused.";
            return;
        }
        if (paused.size() == 1) {
            debuggerState = paused.get(0);
            activeDebuggerSequence = debuggerState.sequence();
            status = "Selected the only paused thread: "
                    + (debuggerState.thread() == null ? "<unknown>" : debuggerState.thread().displayValue());
            jumpToCurrentExecution();
            return;
        }
        int current = paused.indexOf(debuggerState);
        debuggerState = paused.get((current + 1) % paused.size());
        activeDebuggerSequence = debuggerState.sequence();
        tab = Tab.DEBUG;
        requestDebuggerRefresh();
    }

    private void openOrCycleThreads() {
        if (tab != Tab.THREADS) {
            tab = Tab.THREADS;
            if (!tasks.busy()) requestDebuggerRefresh();
            status = "All JVM threads; Enter/F6 pauses, F8 continues, F7 steps a paused thread.";
            return;
        }
        move(1);
        RemoteJvmtiThread selected = selectedDebuggerThread();
        status = selected == null ? "No JVM threads available" : "Selected " + selected.name();
    }

    private RemoteJvmtiThread selectedDebuggerThread() {
        if (debuggerThreads.isEmpty()) return null;
        return debuggerThreads.get(clamp(selections[Tab.THREADS.ordinal()], 0,
                debuggerThreads.size() - 1));
    }

    private JvmDebuggerState selectPausedStateForThread(RemoteJvmtiThread selected) {
        JvmDebuggerState state = pausedStateForThread(selected);
        if (state != null) {
            debuggerState = state;
            activeDebuggerSequence = state.sequence();
            followedThreadName = selected.name();
            return state;
        }
        return null;
    }

    private JvmDebuggerState pausedStateForThread(RemoteJvmtiThread selected) {
        if (selected == null) return null;
        for (JvmDebuggerState state : debuggerStates) {
            if (!state.paused() || state.thread() == null) continue;
            String display = state.thread().displayValue();
            if (display != null && (display.contains("[" + selected.name() + ",")
                    || display.contains(selected.name()))) return state;
        }
        return null;
    }

    private String debuggerThreadLabel(RemoteJvmtiThread thread) {
        JvmDebuggerState stop = pausedStateForThread(thread);
        if (stop == null) return (thread.debuggerPaused() ? ">> [STOP] " : "   ")
                + thread.name() + "  [" + thread.stateSummary() + "]";
        return ">> [STOP:" + stop.reason() + "] " + thread.name() + "  "
                + stop.className() + "." + stop.methodName() + " @" + stop.location();
    }

    private void selectOrPauseThread() {
        RemoteJvmtiThread selected = selectedDebuggerThread();
        if (selected == null) { status = "No JVM thread is selected."; return; }
        followedThreadName = selected.name();
        if (selected.debuggerPaused() && selectPausedStateForThread(selected) != null) {
            tab = Tab.DEBUG;
            jumpToCurrentExecution();
        } else pauseSelectedThread();
    }

    private void pauseSelectedThread() {
        final RemoteJvmtiThread selected = selectedDebuggerThread();
        if (selected == null) {
            status = "Open Threads with T and select a live JVM thread first.";
            tab = Tab.THREADS;
            if (!tasks.busy()) requestDebuggerRefresh();
            return;
        }
        if (selected.debuggerPaused()) {
            selectPausedStateForThread(selected);
            tab = Tab.DEBUG;
            jumpToCurrentExecution();
            return;
        }
        final String name = selected.name();
        followedThreadName = name;
        submit("Pausing thread " + name + "...", new Callable<DebuggerSnapshot>() {
            @Override public DebuggerSnapshot call() {
                session.jvmti().configureDebugger(true);
                selected.pauseInDebugger();
                return debuggerSnapshot();
            }
        }, new Consumer<DebuggerSnapshot>() {
            @Override public void accept(DebuggerSnapshot value) {
                tab = Tab.DEBUG;
                applyDebuggerSnapshot(value, true);
                status = "Paused and following thread " + name;
            }
        });
    }

    private int pausedDebuggerCount() {
        int count = 0;
        for (JvmDebuggerState state : debuggerStates) if (state.paused()) count++;
        return count;
    }

    private void jumpToCurrentExecution() {
        final Tab origin = tab;
        if (origin == Tab.THREADS) selectPausedStateForThread(selectedDebuggerThread());
        if ((debuggerState == null || !debuggerState.paused()) && !liveSampleAvailable) {
            status = "No current BCI is available. Pause a thread, or enable F4 live follow.";
            requestDebuggerRefresh();
            return;
        }
        final boolean sampled = debuggerState == null || !debuggerState.paused();
        final JvmStackFrame frame = executionFrameForJump(origin);
        if (frame == null) {
            requestDebuggerRefresh();
            status = "Loading the selected thread's current stack frames...";
            return;
        }
        selectDebuggerFrame(frame);
        final String className = frame.className();
        final String methodName = frame.methodName();
        final String descriptor = frame.descriptor();
        final long location = frame.location();
        if (location < 0) {
            tab = Tab.FRAMES;
            status = "Frame #" + frame.depth()
                    + " is native (BCI -1); select a Java caller and press G, B, S, or Enter.";
            return;
        }
        if (origin == Tab.SOURCE) {
            if (sourceClass.equals(className) && sourceMethod.equals(methodName)
                    && sourceDescriptor.equals(descriptor)) {
                int line = sourceExecutionLine();
                if (line < 0) {
                    status = "Current frame BCI " + location
                            + " has no decompiled-line mapping; use B/Enter for exact bytecode.";
                } else {
                    alignSourceWithDebugger();
                    status = (sampled ? "Sampled" : "Current") + " frame #" + frame.depth()
                            + " BCI " + location + " is highlighted at decompiled line " + (line + 1);
                }
                return;
            }
            startDecompile(session.findClass(className), methodName, descriptor);
            status = "Decompiling frame #" + frame.depth() + " at BCI " + location + "...";
            return;
        }
        Tab destination = origin == Tab.BYTECODE ? Tab.BYTECODE : Tab.DEBUG;
        tab = destination;
        if (bytecode != null && bytecodeClass.equals(className)
                && bytecodeMethod.equals(methodName)
                && bytecodeDescriptor.equals(descriptor)) {
            alignBytecodeLocation(destination, location);
            status = (sampled ? "Last live sample" : "Current execution")
                    + " frame #" + frame.depth() + " BCI " + location
                    + " is selected and highlighted";
        } else {
            pendingBytecodeLocation = (int) location;
            requestBytecode(className, methodName, descriptor, destination);
        }
    }

    private JvmStackFrame executionFrameForJump(Tab origin) {
        if (debuggerFrames.isEmpty()) return null;
        if (origin == Tab.FRAMES) {
            return debuggerFrames.get(clamp(selections[Tab.FRAMES.ordinal()],
                    0, debuggerFrames.size() - 1));
        }
        if (origin == Tab.SOURCE && !sourceMethod.isEmpty()) {
            JvmStackFrame sourceFrame = executionFrameForMethod(
                    sourceClass, sourceMethod, sourceDescriptor);
            if (sourceFrame != null) return sourceFrame;
        }
        if ((origin == Tab.BYTECODE || origin == Tab.DEBUG) && !bytecodeMethod.isEmpty()) {
            JvmStackFrame bytecodeFrame = executionFrameForMethod(
                    bytecodeClass, bytecodeMethod, bytecodeDescriptor);
            if (bytecodeFrame != null) return bytecodeFrame;
        }
        JvmStackFrame viewed = viewedDebuggerFrame();
        return viewed == null ? actualExecutionFrame() : viewed;
    }

    private JvmStackFrame actualExecutionFrame() {
        for (JvmStackFrame frame : debuggerFrames) {
            if (frame.depth() == 0) return frame;
        }
        return debuggerFrames.isEmpty() ? null : debuggerFrames.get(0);
    }

    private long currentExecutionLocationForBytecode() {
        return executionLocationForMethod(
                bytecodeClass, bytecodeMethod, bytecodeDescriptor);
    }

    private void closeDebuggerStates() {
        for (JvmDebuggerState state : debuggerStates) state.close();
        for (JvmDebuggerLocal local : debuggerLocals) local.close();
        for (RemoteJvmtiThread thread : debuggerThreads) thread.close();
        debuggerStates.clear();
        debuggerFrames.clear();
        debuggerLocals.clear();
        debuggerThreads.clear();
        debuggerLocalsError = "";
        debuggerState = null;
        liveSampleAvailable = false;
    }

    private void toggleBreakpoint(boolean receiverOnly) {
        if (tab == Tab.BREAKPOINTS) {
            clearSelectedBreakpoint();
            return;
        }
        if (tab == Tab.BROWSE && !visibleBrowserEntries.isEmpty()) {
            TuiBrowserEntry entry = visibleBrowserEntries.get(selection());
            JvmClassPathCatalog.Member member = entry.unloadedMember();
            if (entry.unloaded() && entry.kind() == TuiBrowserEntry.Kind.METHOD && member != null) {
                if (receiverOnly) {
                    status = "An unloaded class has no receiver object; F9 creates an all-instance pending breakpoint.";
                } else if (member.isNative() || member.isAbstract()) {
                    toggleMethodEventBreakpoint(true);
                } else {
                    toggleBreakpointSpec(new BreakpointSpec(entry.ownerName(), member.name(),
                            member.descriptor(), 0L, -1), " at unloaded method entry", false);
                }
                return;
            }
        }
        if (tab == Tab.SOURCE) {
            toggleSourceBreakpoint(receiverOnly);
            return;
        }
        if (tab == Tab.METHODS) {
            if (unloadedContextClass != null) {
                JvmClassPathCatalog.Member member = selectedUnloadedMethod();
                if (member == null) { status = "Select an unloaded method first."; return; }
                if (receiverOnly) {
                    status = "An unloaded class has no receiver object; F9 creates an all-instance pending breakpoint.";
                } else if (member.isNative() || member.isAbstract()) {
                    toggleMethodEventBreakpoint(true);
                } else {
                    toggleBreakpointSpec(new BreakpointSpec(unloadedContextClass.name(), member.name(),
                            member.descriptor(), 0L, -1), " at unloaded method entry", false);
                }
                return;
            }
            toggleMethodEntryBreakpoint(receiverOnly);
            return;
        }
        if (!bytecodeClass.isEmpty()
                && session.instrumentation().bytecode().hasStaged(bytecodeClass)) {
            status = "This BCI belongs to staged code. Press F3 to flush it before installing a breakpoint.";
            return;
        }
        if (bytecode == null || bytecode.instructions().isEmpty()) {
            status = "Load a method bytecode view first; F9 applies to the highlighted BCI.";
            return;
        }
        final BytecodeInstruction instruction = bytecode.instructions().get(bytecodeCursor());
        final BreakpointSpec spec = new BreakpointSpec(
                bytecodeClass, bytecodeMethod, bytecodeDescriptor, instruction.offset(), instruction.sourceLine());
        toggleBreakpointSpec(spec, instruction.mnemonic().startsWith("invoke") ? " before invoke" : "",
                receiverOnly);
    }

    private void insertBytecode() throws IOException {
        final BytecodeInstruction instruction = selectedBytecodeForEdit();
        if (instruction == null) return;
        String entered = editText("Insert ASM before BCI " + instruction.offset()
                + " ('after: ' prefix inserts after; separate instructions with ;;)", "");
        if (entered == null || entered.trim().isEmpty()) return;
        final boolean after = entered.trim().toLowerCase(Locale.ROOT).startsWith("after:");
        final String assembly = after ? entered.trim().substring("after:".length()).trim() : entered.trim();
        if (assembly.isEmpty()) { status = "Assembly must not be empty."; return; }
        JvmBytecodePatch.Builder builder = JvmBytecodePatch.builder(bytecodeClass);
        if (after) builder.insertAfter(bytecodeMethod, bytecodeDescriptor,
                instruction.offset(), assembly);
        else builder.insertBefore(bytecodeMethod, bytecodeDescriptor,
                instruction.offset(), assembly);
        applyBytecodeEdit(after ? "Inserting bytecode after BCI " : "Inserting bytecode before BCI ",
                instruction.offset(), builder.build());
    }

    private void replaceBytecode() throws IOException {
        final BytecodeInstruction instruction = selectedBytecodeForEdit();
        if (instruction == null) return;
        final String assembly = editText("Replace BCI " + instruction.offset()
                + " with ASM (separate instructions with ;;)", "");
        if (assembly == null || assembly.trim().isEmpty()) return;
        JvmBytecodePatch patch = JvmBytecodePatch.builder(bytecodeClass)
                .replace(bytecodeMethod, bytecodeDescriptor,
                        instruction.offset(), assembly.trim()).build();
        applyBytecodeEdit("Replacing bytecode at BCI ", instruction.offset(), patch);
    }

    private void deleteBytecode() throws IOException {
        final BytecodeInstruction instruction = selectedBytecodeForEdit();
        if (instruction == null) return;
        String confirmation = editText("Delete BCI " + instruction.offset() + " "
                + instruction.mnemonic() + "? Type yes", "");
        if (!"yes".equalsIgnoreCase(confirmation == null ? "" : confirmation.trim())) {
            status = "Bytecode deletion cancelled.";
            return;
        }
        JvmBytecodePatch patch = JvmBytecodePatch.builder(bytecodeClass)
                .delete(bytecodeMethod, bytecodeDescriptor, instruction.offset()).build();
        applyBytecodeEdit("Deleting bytecode at BCI ", instruction.offset(), patch);
    }

    private void editExceptionHandlers() throws IOException {
        if (bytecode == null || bytecodeClass.isEmpty() || bytecodeMethod.isEmpty()) {
            status = "Load a Java bytecode method before editing its exception table.";
            return;
        }
        String entered = editText("Handlers: list | add <start> <end> <handler> [type|any] | delete <index>",
                "list");
        if (entered == null || entered.trim().isEmpty()) return;
        String[] values = entered.trim().split("\\s+");
        if ("list".equalsIgnoreCase(values[0])) {
            List<nhcm.jvmrtdp.api.bytecode.JvmExceptionHandlerInfo> handlers =
                    session.instrumentation().bytecode().exceptionHandlers(
                            bytecodeClass, bytecodeMethod, bytecodeDescriptor);
            if (handlers.isEmpty()) status = "Exception handlers: <none>";
            else {
                StringBuilder summary = new StringBuilder("Exception handlers: ");
                for (int index = 0; index < handlers.size(); index++) {
                    if (index > 0) summary.append("; ");
                    summary.append(handlers.get(index));
                }
                status = summary.toString();
            }
            return;
        }
        try {
            JvmBytecodePatch.Builder builder = JvmBytecodePatch.builder(bytecodeClass);
            if ("add".equalsIgnoreCase(values[0]) && (values.length == 4 || values.length == 5)) {
                builder.addExceptionHandler(bytecodeMethod, bytecodeDescriptor,
                        Integer.decode(values[1]).intValue(), Integer.decode(values[2]).intValue(),
                        Integer.decode(values[3]).intValue(), values.length == 5 ? values[4] : null);
            } else if ("delete".equalsIgnoreCase(values[0]) && values.length == 2) {
                builder.deleteExceptionHandler(bytecodeMethod, bytecodeDescriptor,
                        Integer.decode(values[1]).intValue());
            } else {
                status = "Expected: list | add <start> <end> <handler> [type|any] | delete <index>";
                return;
            }
            int anchor = bytecode.instructions().isEmpty() ? 0
                    : bytecode.instructions().get(bytecodeCursor()).offset();
            applyBytecodeEdit("Staging exception-table edit at BCI ", anchor, builder.build());
        } catch (NumberFormatException failure) {
            status = "Invalid numeric exception-handler BCI/index.";
        }
    }

    private BytecodeInstruction selectedBytecodeForEdit() {
        if (bytecode == null || bytecode.instructions().isEmpty()
                || bytecodeClass.isEmpty() || bytecodeMethod.isEmpty()) {
            status = "Load a Java bytecode method before editing it.";
            return null;
        }
        return bytecode.instructions().get(bytecodeCursor());
    }

    private void applyBytecodeEdit(String activity, final int oldBci,
            final JvmBytecodePatch patch) {
        final String className = bytecodeClass;
        final String methodName = bytecodeMethod;
        final String descriptor = bytecodeDescriptor;
        final Tab destination = tab;
        submit(activity + oldBci + "...", new Callable<JvmBytecodePatchResult>() {
            @Override public JvmBytecodePatchResult call() {
                return session.instrumentation().bytecode().stage(patch);
            }
        }, new Consumer<JvmBytecodePatchResult>() {
            @Override public void accept(JvmBytecodePatchResult result) {
                synchronizedBytecodeRevision = session.instrumentation().bytecode().revision();
                Long relocated = result.relocatedBci(methodName, descriptor, oldBci);
                pendingBytecodeLocation = relocated == null ? oldBci : relocated.intValue();
                synchronizeManagedControls();
                status = "Staged " + result.operationCount() + " edit(s); F3 flushes the complete "
                        + "transaction, Shift+F3 discards it. Reloading staged bytecode...";
                requestBytecode(className, methodName, descriptor, destination);
            }
        });
    }

    private void flushBytecodeEdits() {
        if (bytecodeClass.isEmpty() || !session.instrumentation().bytecode().hasStaged(bytecodeClass)) {
            status = "No staged bytecode edits for the viewed class.";
            return;
        }
        final String className = bytecodeClass;
        final String methodName = bytecodeMethod;
        final String descriptor = bytecodeDescriptor;
        final Tab destination = tab;
        submit("Verifying and flushing all staged edits for " + className + "...",
                new Callable<JvmBytecodePatchResult>() {
                    @Override public JvmBytecodePatchResult call() {
                        return session.instrumentation().bytecode().flush(className);
                    }
                }, new Consumer<JvmBytecodePatchResult>() {
                    @Override public void accept(JvmBytecodePatchResult result) {
                        synchronizedBytecodeRevision = session.instrumentation().bytecode().revision();
                        synchronizeManagedControls();
                        status = "Flushed " + result.operationCount() + " bytecode edit(s) for "
                                + className + "; refreshing debugger and bytecode views...";
                        requestBytecode(className, methodName, descriptor, destination);
                        requestDebuggerRefresh();
                    }
                });
    }

    private void discardBytecodeEdits() {
        if (bytecodeClass.isEmpty() || !session.instrumentation().bytecode().hasStaged(bytecodeClass)) {
            status = "No staged bytecode edits for the viewed class.";
            return;
        }
        session.instrumentation().bytecode().discard(bytecodeClass);
        synchronizedBytecodeRevision = session.instrumentation().bytecode().revision();
        status = "Discarded staged edits for " + bytecodeClass + "; reloading live bytecode...";
        requestBytecode(bytecodeClass, bytecodeMethod, bytecodeDescriptor, tab);
    }

    private void toggleMethodEntryBreakpoint(boolean receiverOnly) {
        RemoteMethod method = selectedMethodForAction();
        if (method == null) {
            status = "Select a method first.";
            return;
        }
        if (method.isNative() || method.isAbstract()) {
            status = method.implementationKind() + " methods have no Java bytecode entry to break on.";
            return;
        }
        final BreakpointSpec spec = new BreakpointSpec(method.declaringClass(), method.name(),
                method.descriptor(), 0L, -1);
        toggleBreakpointSpec(spec, " at method entry", receiverOnly);
    }

    private void toggleSelectedFieldWatch(final boolean modification) {
        if (tab == Tab.BROWSE && !visibleBrowserEntries.isEmpty()) {
            TuiBrowserEntry entry = visibleBrowserEntries.get(selection());
            if (entry.unloaded() && entry.kind() == TuiBrowserEntry.Kind.FIELD
                    && entry.unloadedMember() != null) {
                toggleUnloadedFieldWatch(entry, modification);
                return;
            }
        }
        if (tab == Tab.FIELDS && unloadedContextClass != null) {
            JvmClassPathCatalog.Member member = selectedUnloadedField();
            if (member != null) toggleUnloadedFieldWatch(
                    TuiBrowserEntry.unloadedMember(unloadedContextClass, member), modification);
            return;
        }
        if (tab != Tab.FIELDS) {
            status = "U/W set field-read/field-write watchpoints from the Fields view.";
            return;
        }
        List<RemoteField> visible = visibleFields();
        if (visible.isEmpty()) return;
        final RemoteField field = visible.get(selection());
        final String kind = modification ? "write" : "read";
        // TUI watchpoints target the field for every instance. Receiver-specific watches
        // remain available only through the explicit CLI/library condition APIs.
        final JvmFieldWatchInfo existing = findFieldWatch(field, modification, 0L);
        final boolean set = existing == null;
        submit((set ? "Setting " : "Clearing ") + kind + " watchpoint on " + field.name() + "...",
                new Callable<Boolean>() {
                    @Override public Boolean call() {
                        if (set) session.jvmti().configureDebugger(true);
                        if (set) session.jvmti().setFieldWatch(field.declaringClass(), field.name(),
                                field.descriptor(), modification, null, true);
                        else {
                            // Clear by the stable registration id even if its original object handle closed.
                            session.jvmti().clearFieldWatch(existing);
                        }
                        return Boolean.valueOf(set);
                    }
                }, new Consumer<Boolean>() {
                    @Override public void accept(Boolean enabled) {
                        synchronizeManagedControls();
                        status = kind + " watchpoint "
                                + (enabled.booleanValue() ? "set" : "cleared") + " on "
                                + field.declaringClass() + "." + field.name();
                    }
                });
    }

    private void toggleUnloadedFieldWatch(final TuiBrowserEntry entry,
            final boolean modification) {
        final JvmClassPathCatalog.Member member = entry.unloadedMember();
        final String owner = entry.ownerName();
        final String kind = modification ? "write" : "read";
        final JvmFieldWatchInfo existing = findFieldWatch(
                owner, member.name(), member.descriptor(), modification, 0L);
        final boolean set = existing == null;
        submit((set ? "Registering pending " : "Clearing pending ") + kind
                        + " watch on " + owner + "." + member.name() + "...",
                new Callable<Boolean>() {
                    @Override public Boolean call() {
                        if (set) {
                            session.jvmti().configureDebugger(true);
                            session.jvmti().setFieldWatch(owner, member.name(), member.descriptor(),
                                    modification, null, true);
                        } else session.jvmti().clearFieldWatch(existing);
                        return Boolean.valueOf(set);
                    }
                }, new Consumer<Boolean>() {
                    @Override public void accept(Boolean enabled) {
                        synchronizeManagedControls();
                        status = (enabled.booleanValue() ? "Pending " : "Cleared ") + kind
                                + " watch for unloaded " + owner + "." + member.name()
                                + (enabled.booleanValue()
                                        ? "; it installs automatically at ClassPrepare" : "");
                    }
                });
    }

    private void toggleSourceBreakpoint(boolean receiverOnly) {
        if (sourceMethod.isEmpty()) {
            status = "F9 in Decompile requires a single-method result; press S from Methods or Debug.";
            return;
        }
        if (sourceBciToLine.isEmpty()) {
            status = "This decompiler produced no line/BCI map. Switch to CFR with E and decompile again.";
            return;
        }
        final int selectedLine = selections[Tab.SOURCE.ordinal()] + 1;
        Map.Entry<Integer, Integer> best = null;
        int distance = Integer.MAX_VALUE;
        for (Map.Entry<Integer, Integer> mapping : sourceBciToLine.entrySet()) {
            int candidate = Math.abs(mapping.getValue() - selectedLine);
            if (candidate < distance) { best = mapping; distance = candidate; }
        }
        if (best == null) { status = "No mapped bytecode exists near this decompiled line."; return; }
        final BreakpointSpec spec = new BreakpointSpec(sourceClass, sourceMethod, sourceDescriptor,
                best.getKey(), best.getValue());
        final int mappedLine = best.getValue();
        toggleBreakpointSpec(spec, " at decompiled line " + mappedLine
                + (mappedLine == selectedLine ? "" : " (nearest mapped line)"), receiverOnly);
    }

    private void toggleBreakpointSpec(final BreakpointSpec requested, final String detail,
            boolean receiverOnly) {
        final JvmBreakpointCondition condition;
        if (receiverOnly) {
            if (!session.context().isObject()) {
                status = "Shift+F9 needs an object Context; F9 creates a normal breakpoint.";
                return;
            }
            if (isStaticBreakpointTarget(requested)) {
                status = "A static method has no receiver; use F9 for a normal breakpoint.";
                return;
            }
            condition = JvmBreakpointCondition.receiver(session.context().remoteObject());
        } else {
            condition = JvmBreakpointCondition.any();
        }
        final long receiverId = condition.receiverId();
        final BreakpointSpec existing = findBreakpoint(requested.className, requested.methodName,
                requested.descriptor, requested.bci, receiverId);
        final boolean set = existing == null;
        submit((set ? "Setting" : "Clearing") + " breakpoint at BCI " + requested.bci + "...",
                new Callable<Boolean>() {
                    @Override public Boolean call() {
                        if (set) {
                            session.jvmti().configureDebugger(true);
                            session.jvmti().setBreakpoint(requested.className, requested.methodName,
                                    requested.descriptor, requested.bci, condition, true);
                        } else session.jvmti().clearBreakpoint(existing.info());
                        return Boolean.valueOf(set);
                    }
                }, new Consumer<Boolean>() {
                    @Override public void accept(Boolean enabled) {
                        synchronizeManagedControls();
                        status = (enabled.booleanValue() ? "Breakpoint set" : "Breakpoint cleared")
                                + detail + " at " + requested.className + "." + requested.methodName
                                + " BCI " + requested.bci
                                + (receiverOnly ? " for the current object only" : "");
                    }
                });
    }

    private boolean isStaticBreakpointTarget(BreakpointSpec requested) {
        if ("<clinit>".equals(requested.methodName)) return true;
        if (bytecode != null && requested.className.equals(bytecodeClass)
                && requested.methodName.equals(bytecodeMethod)
                && requested.descriptor.equals(bytecodeDescriptor)) {
            return Modifier.isStatic(bytecode.accessFlags());
        }
        for (RemoteMethod method : methods) {
            if (method.declaringClass().equals(requested.className)
                    && method.name().equals(requested.methodName)
                    && method.descriptor().equals(requested.descriptor)) return method.isStatic();
        }
        return false;
    }

    private BreakpointSpec findBreakpoint(String className, String methodName,
            String descriptor, long bci, long receiverId) {
        for (BreakpointSpec value : breakpoints.values()) {
            if (value.className.equals(className) && value.methodName.equals(methodName)
                    && value.descriptor.equals(descriptor) && value.bci == bci
                    && value.receiverId == receiverId) return value;
        }
        return null;
    }

    /** Matches the physical bytecode location regardless of optional SDK conditions. */
    private boolean hasBreakpointAt(String className, String methodName,
            String descriptor, long bci) {
        for (BreakpointSpec value : breakpoints.values()) {
            if (value.className.equals(className) && value.methodName.equals(methodName)
                    && value.descriptor.equals(descriptor) && value.bci == bci) return true;
        }
        return false;
    }

    private JvmFieldWatchInfo findFieldWatch(
            RemoteField field, boolean modification, long receiverId) {
        return findFieldWatch(field.declaringClass(), field.name(), field.descriptor(),
                modification, receiverId);
    }

    private JvmFieldWatchInfo findFieldWatch(String className, String fieldName,
            String descriptor, boolean modification, long receiverId) {
        for (JvmFieldWatchInfo value : fieldWatches.values()) {
            if (value.className().equals(className)
                    && value.fieldName().equals(fieldName)
                    && value.descriptor().equals(descriptor)
                    && value.modification() == modification
                    && value.receiverId() == receiverId) return value;
        }
        return null;
    }

    private void dumpContextClass() {
        if (unloadedContextClass != null) {
            final JvmClassPathCatalog.ClassEntry type = unloadedContextClass;
            final Path output = Paths.get("dumps", type.name().replace('.', '/') + ".class")
                    .toAbsolutePath().normalize();
            submit("Dumping unloaded " + type.name() + "...", new Callable<Long>() {
                @Override public Long call() throws IOException {
                    Files.createDirectories(output.getParent());
                    Files.write(output, type.bytes());
                    return Files.size(output);
                }
            }, new Consumer<Long>() {
                @Override public void accept(Long size) {
                    status = "Dumped " + size + " unloaded class byte(s) to " + output;
                }
            });
            return;
        }
        if (!requireContext()) return;
        final RemoteClass type = contextClass;
        final Path output = Paths.get("dumps", type.className().replace('.', '/') + ".class")
                .toAbsolutePath().normalize();
        submit("Dumping " + type.className() + "...", new Callable<Long>() {
            @Override public Long call() throws IOException {
                type.dumpClass(output);
                return Files.size(output);
            }
        }, new Consumer<Long>() {
            @Override public void accept(Long size) {
                status = "Dumped " + size + " bytes to " + output;
            }
        });
    }

    private void exportCurrentView() throws IOException {
        if (tasks.userOperationBusy()) { status = busyMessage(); return; }
        final ExportPayload payload = exportPayload();
        if (payload == null) {
            status = "Nothing is loaded in the current view.";
            return;
        }
        Path suggested = Paths.get("exports", Long.toString(session.server().process().pid()),
                safeFileName(payload.baseName) + payload.extension).toAbsolutePath().normalize();
        String requested = editText("Export " + tab.name().toLowerCase(Locale.ROOT) + " view (Ctrl+U clears)",
                suggested.toString());
        if (requested == null) return;
        if (requested.trim().isEmpty()) requested = suggested.toString();
        final Path output = Paths.get(requested.trim()).toAbsolutePath().normalize();
        submit("Exporting " + tab.name().toLowerCase(Locale.ROOT) + "...", new Callable<Long>() {
            @Override public Long call() throws IOException {
                Path parent = output.getParent();
                if (parent != null) Files.createDirectories(parent);
                byte[] bytes = payload.content.getBytes(StandardCharsets.UTF_8);
                Files.write(output, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                return (long) bytes.length;
            }
        }, new Consumer<Long>() {
            @Override public void accept(Long bytes) {
                status = "Exported " + bytes + " byte(s) to " + output;
            }
        });
    }

    private ExportPayload exportPayload() {
        List<String> lines = new ArrayList<String>();
        String baseName = tab.name().toLowerCase(Locale.ROOT);
        String extension = ".txt";
        if (tab == Tab.BROWSE) {
            lines.add("JVMRTDP browser export");
            lines.add(browserTitle);
            lines.add("filter=" + (browserFilter.isEmpty() ? "<none>" : browserFilter));
            lines.add("");
            for (TuiBrowserEntry entry : visibleBrowserEntries) lines.add(entry.displayName());
            baseName = browserTitle;
        } else if (tab == Tab.CONTEXT) {
            if (unloadedContextClass != null) {
                lines.add("UNLOADED CLASS CONTEXT");
                lines.addAll(contextLines);
                baseName = unloadedContextClass.name() + "-context";
            } else {
                if (!session.context().isSet()) return null;
                lines.add("CONTEXT STACK");
                List<String> stack = session.context().stack(1024);
                for (int index = 0; index < stack.size(); index++) lines.add("#" + index + " " + stack.get(index));
                lines.add("");
                lines.add("CONTEXT VALUE");
                lines.addAll(contextLines);
                baseName = session.context().description();
            }
        } else if (tab == Tab.REFERENCES) {
            lines.add("TRACKED REFERENCES");
            for (JvmReferenceInfo reference : session.references().snapshot()) {
                lines.add(reference.toString());
                lines.add("  source=" + reference.source() + " assignable=" + reference.assignable()
                        + (reference.error().isEmpty() ? "" : " error=" + reference.error()));
            }
            baseName = "tracked-references";
        } else if (tab == Tab.STRINGS) {
            lines.add("STRING HOOKS");
            for (JvmStringHookInfo hook : session.stringHooks().snapshot()) lines.add(hook.toString());
            baseName = "string-hooks";
        } else if (tab == Tab.FIELDS) {
            if (unloadedContextClass != null) {
                lines.add("Fields of unloaded " + unloadedContextClass.name());
                for (JvmClassPathCatalog.Member field : visibleUnloadedFields()) {
                    lines.add(String.format("%s %s %s  descriptor=%s",
                            field.isStatic() ? "static" : "instance", field.typeSummary(),
                            field.name(), field.descriptor()));
                }
                baseName = unloadedContextClass.name() + "-unloaded-fields";
            } else {
                if (contextClass == null) return null;
                lines.add("Fields of " + contextClass.className());
                lines.add("mode=" + (classContext ? "class metadata (static + virtual)" : "instance"));
                lines.add("");
                for (RemoteField field : visibleFields()) {
                    lines.add(String.format("%s %s %s  descriptor=%s  declaredBy=%s",
                            field.isStatic() ? "static" : "instance", field.typeName(), field.name(),
                            field.descriptor(), field.declaringClass()));
                }
                baseName = contextClass.className() + "-fields";
            }
        } else if (tab == Tab.METHODS) {
            if (unloadedContextClass != null) {
                lines.add("Methods of unloaded " + unloadedContextClass.name());
                for (JvmClassPathCatalog.Member method : visibleUnloadedMethods()) {
                    lines.add(String.format("%s %s %s  descriptor=%s",
                            method.isStatic() ? "static" : "instance", method.typeSummary(),
                            method.name(), method.descriptor()));
                }
                baseName = unloadedContextClass.name() + "-unloaded-methods";
            } else {
                if (contextClass == null) return null;
                lines.add("Methods of " + contextClass.className());
                lines.add("mode=" + (classContext ? "class metadata (static + virtual)" : "instance"));
                lines.add("");
                for (RemoteMethod method : visibleMethods()) {
                    lines.add(String.format("%s %s %s%s  declaredBy=%s",
                            method.isStatic() ? "static" : "instance", method.returnTypeName(), method.name(),
                            method.parameterTypeNames(), method.declaringClass()));
                    lines.add("  descriptor=" + method.descriptor());
                }
                baseName = contextClass.className() + "-methods";
            }
        } else if (tab == Tab.SOURCE) {
            if (sourceLines.isEmpty()) return null;
            lines.addAll(sourceLines);
            baseName = sourceTitle;
            extension = ".java";
        } else if (tab == Tab.BYTECODE || tab == Tab.DEBUG) {
            if (bytecode == null) return null;
            lines.add(bytecodeClass + "." + bytecodeMethod + bytecodeDescriptor);
            lines.add("maxStack=" + bytecode.maxStack() + " maxLocals=" + bytecode.maxLocals());
            lines.add("");
            int index = 0;
            for (BytecodeInstruction instruction : bytecode.instructions()) {
                lines.add(String.format("#%04d BCI=%05d line=%-5s %-16s %s", index++, instruction.offset(),
                        instruction.sourceLine() < 0 ? "-" : Integer.toString(instruction.sourceLine()),
                        instruction.mnemonic(), instruction.operands()));
            }
            if (tab == Tab.DEBUG) {
                lines.add("");
                lines.addAll(debuggerSidePanel());
            }
            baseName = bytecodeClass + "." + bytecodeMethod + (tab == Tab.DEBUG ? "-debug" : "-bytecode");
            extension = tab == Tab.DEBUG ? ".debug.txt" : ".bytecode.txt";
        } else if (tab == Tab.THREADS) {
            lines.add("JVM THREADS (" + debuggerThreads.size() + ")");
            for (RemoteJvmtiThread thread : debuggerThreads) {
                JvmDebuggerState stop = pausedStateForThread(thread);
                lines.add(thread.name() + "  " + thread.stateSummary()
                        + (stop == null ? "" : "  STOP=" + stop.reason() + " "
                                + stop.className() + "." + stop.methodName() + "@" + stop.location())
                        + "  priority=" + thread.priority() + " daemon=" + thread.daemon());
            }
            baseName = "jvm-threads";
        }
        return new ExportPayload(baseName, extension, joinLines(lines));
    }

    private boolean horizontallyScrollable() {
        return tab == Tab.SOURCE || tab == Tab.BYTECODE || tab == Tab.DEBUG
                || tab == Tab.REFERENCES || tab == Tab.STRINGS;
    }

    private void moveHorizontal(int delta) {
        int index = tab.ordinal();
        horizontalOffsets[index] = clamp(horizontalOffsets[index] + delta, 0, maximumHorizontalOffset());
        status = horizontalOffsets[index] == 0 ? "Horizontal viewport reset"
                : "Horizontal offset " + horizontalOffsets[index] + " ([ / ] scroll, 0 resets)";
    }

    private void resetHorizontal() {
        horizontalOffsets[tab.ordinal()] = 0;
        status = "Horizontal viewport reset";
    }

    private void toggleInspector() {
        if (tab != Tab.BYTECODE && tab != Tab.DEBUG) {
            status = "The inspector is available in Bytecode and Debug views.";
            return;
        }
        inspectorVisibleOverride = Boolean.TRUE;
        inspectorFocused = !inspectorFocused;
        status = inspectorFocused
                ? "Debugger info focused: arrows/PgUp/PgDn scroll, I or Esc returns to bytecode"
                : "Debugger info returned to the side panel";
    }

    private boolean inspectorVisible(int width) {
        boolean requested = inspectorVisibleOverride == null ? width >= 90
                : inspectorVisibleOverride.booleanValue();
        return requested && width >= 90;
    }

    private boolean handleInspectorNavigation(int key) {
        if (!inspectorFocused || (tab != Tab.BYTECODE && tab != Tab.DEBUG)) return false;
        List<String> lines = debuggerSidePanel();
        int page = Math.max(1, screen.height() - 7);
        if (key == 'i' || key == 'I' || key == TuiKey.ESCAPE) {
            toggleInspector();
            return true;
        }
        if (key == TuiKey.UP) inspectorScroll--;
        else if (key == TuiKey.DOWN) inspectorScroll++;
        else if (key == TuiKey.PAGE_UP) inspectorScroll -= page;
        else if (key == TuiKey.PAGE_DOWN) inspectorScroll += page;
        else if (key == TuiKey.HOME) inspectorScroll = 0;
        else if (key == TuiKey.END) inspectorScroll = Math.max(0, lines.size() - 1);
        else if (key == TuiKey.LEFT || key == '[') inspectorHorizontal -= 8;
        else if (key == TuiKey.RIGHT || key == ']') inspectorHorizontal += 8;
        else if (key == '0') inspectorHorizontal = 0;
        else return false;
        inspectorScroll = clamp(inspectorScroll, 0, Math.max(0, lines.size() - 1));
        inspectorHorizontal = clamp(inspectorHorizontal, 0,
                Math.max(0, TuiViewport.maximumWidth(lines) - 1));
        return true;
    }

    private int maximumHorizontalOffset() {
        if (inspectorFocused) {
            return Math.max(0, TuiViewport.maximumWidth(debuggerSidePanel()) - 1);
        }
        if (tab == Tab.SOURCE) return Math.max(0, TuiViewport.maximumWidth(sourceLines) - 1);
        if (tab == Tab.BYTECODE || tab == Tab.DEBUG) {
            if (bytecode == null) return 0;
            int maximum = 0;
            for (BytecodeInstruction instruction : bytecode.instructions()) {
                maximum = Math.max(maximum, instructionText(instruction).length());
            }
            return Math.max(0, maximum - 1);
        }
        return Math.max(0, Math.max(TuiViewport.maximumWidth(leftLines()),
                TuiViewport.maximumWidth(rightLines())) - 1);
    }

    private void navigateBack() {
        if (tasks.userOperationBusy()) { status = busyMessage(); return; }
        if (tab == Tab.FRAMES || tab == Tab.LOCALS || tab == Tab.BREAKPOINTS) {
            tab = Tab.DEBUG;
            alignDebuggerLocation(Tab.DEBUG);
            status = "Returned to Debug";
            return;
        }
        if (tab == Tab.BROWSE) {
            if (browseUnloaded && !unloadedMemberOwner.isEmpty()) {
                String ownerPackage = TuiBrowserModel.parentPackage(unloadedMemberOwner);
                unloadedMemberOwner = "";
                requestPackage(ownerPackage);
                return;
            }
            if (searchMode) requestPackage(packageName);
            else if (!packageName.isEmpty()) requestPackage(TuiBrowserModel.parentPackage(packageName));
            return;
        }
        if (unloadedContextClass != null) {
            tab = Tab.BROWSE;
            packageName = TuiBrowserModel.parentPackage(unloadedContextClass.name());
            requestUnloadedPackage(packageName, false);
            status = "Returned to unloaded package "
                    + (packageName.isEmpty() ? "<root>" : packageName);
            return;
        }
        if (session.context().isSet() && session.context().depth() > 1) {
            session.context().back();
            tab = Tab.CONTEXT;
            requestContextRefresh();
        } else status = "Context stack has no previous item.";
    }

    private void clearContext() {
        if (tasks.userOperationBusy()) { status = busyMessage(); return; }
        session.context().clear();
        clearUnloadedContext();
        selectedMethod = null;
        clearContextView();
        tab = Tab.BROWSE;
        status = "Context and context stack cleared.";
    }

    private void clearContextView() {
        if (unloadedContextClass != null) return;
        contextClass = null;
        fields.clear();
        methods.clear();
        contextLines.clear();
        contextLines.add("No context selected.");
        contextLines.add("Choose a class from Browse. Class context exposes static + virtual metadata;");
        contextLines.add("reading a field pushes an object context with instance members.");
    }

    private void refresh() {
        if (tasks.userOperationBusy()) { status = busyMessage(); return; }
        if (tab == Tab.REFERENCES) requestReferenceRefresh();
        else if (tab == Tab.STRINGS) {
            requestDebuggerRefresh();
            status = "Refreshing debugger hits for String hooks...";
        }
        else if (tab == Tab.BROWSE) {
            if (browseUnloaded) requestUnloadedPackage(packageName, true);
            else if (searchMode && !lastSearch.isEmpty()) requestSearch(lastSearch);
            else requestPackage(packageName);
        } else if (tab == Tab.DEBUG || tab == Tab.FRAMES
                || tab == Tab.LOCALS || tab == Tab.THREADS) requestDebuggerRefresh();
        else if (session.context().isSet()) requestContextRefresh();
    }

    private void toggleRuntime() {
        showRuntime = !showRuntime;
        status = showRuntime ? "Runtime/JDK classes are visible"
                : "Runtime/JDK classes and generated lambdas are hidden";
        if (searchMode && !lastSearch.isEmpty()) requestSearch(lastSearch);
        else requestPackage(packageName);
    }

    private void toggleArrays() {
        if (tasks.userOperationBusy()) { status = busyMessage(); return; }
        if (browseUnloaded) {
            status = "Array classes are created by the VM and have no standalone class file; "
                    + "press U for loaded classes, then a to show arrays.";
            return;
        }
        showArrays = !showArrays;
        status = showArrays
                ? "Array classes enabled; opening package root (arrays have no Java package or <clinit>)"
                : "Array classes hidden";
        if (searchMode && !lastSearch.isEmpty()) requestSearch(lastSearch);
        else requestPackage(showArrays ? "" : packageName);
    }

    private void toggleSpecialMethods() {
        if (tasks.userOperationBusy()) { status = busyMessage(); return; }
        showSpecialMethods = !showSpecialMethods;
        status = showSpecialMethods
                ? "JVM lifecycle methods enabled: loading <init> and <clinit> from class bytes"
                : "JVM lifecycle methods hidden";
        if (unloadedContextClass != null) {
            clampMemberSelections();
            status = showSpecialMethods
                    ? "JVM lifecycle methods visible in unloaded class context"
                    : "JVM lifecycle methods hidden";
        } else if (session.context().isSet() && !tasks.userOperationBusy()) requestContextRefresh();
        else if (!session.context().isSet()) tab = Tab.BROWSE;
    }

    private void toggleInheritedObjectMethods() {
        if (tab != Tab.METHODS) {
            status = "H toggles inherited java.lang.Object methods from the Methods view.";
            return;
        }
        hideInheritedObjectMethods = !hideInheritedObjectMethods;
        clampMemberSelections();
        List<RemoteMethod> visible = visibleMethods();
        selectedMethod = visible.isEmpty() ? null : visible.get(selections[Tab.METHODS.ordinal()]);
        status = hideInheritedObjectMethods
                ? "Hidden unoverridden java.lang.Object methods; real overrides remain visible"
                : "Inherited java.lang.Object methods are visible";
    }

    private void toggleStaticMembers() {
        showStaticMembers = !showStaticMembers;
        clampMemberSelections();
        if (tab == Tab.METHODS) {
            if (unloadedContextClass == null) {
                List<RemoteMethod> visible = visibleMethods();
                selectedMethod = visible.isEmpty() ? null : visible.get(selections[Tab.METHODS.ordinal()]);
            }
        }
        status = showStaticMembers
                ? "Static members are visible (@ toggles); virtual members are "
                        + (showVirtualMembers ? "visible" : "hidden") + " (# toggles)"
                : "Static members are hidden; press @ to show them again";
    }

    private void toggleVirtualMembers() {
        showVirtualMembers = !showVirtualMembers;
        clampMemberSelections();
        if (tab == Tab.METHODS) {
            if (unloadedContextClass == null) {
                List<RemoteMethod> visible = visibleMethods();
                selectedMethod = visible.isEmpty() ? null : visible.get(selections[Tab.METHODS.ordinal()]);
            }
        }
        status = showVirtualMembers
                ? "Instance fields and virtual methods are visible (# toggles)"
                : "Instance fields and virtual methods are hidden; press # to show them again";
    }

    private void toggleEngine() {
        engine = engine == DecompilerEngine.CFR ? DecompilerEngine.PROCYON : DecompilerEngine.CFR;
        status = "Decompiler changed to " + engine + "; press S or A to decompile.";
    }

    private void editFilter() throws IOException {
        if (tab == Tab.BROWSE) {
            String value = editText("Local browser filter", browserFilter);
            if (value != null) {
                browserFilter = value;
                applyBrowserFilter();
                status = visibleBrowserEntries.size() + " locally filtered item(s)";
            }
        } else if (tab == Tab.FIELDS || tab == Tab.METHODS) {
            String value = editText("Member filter", memberFilter);
            if (value != null) {
                memberFilter = value;
                clampMemberSelections();
                status = "Member filter: " + (memberFilter.isEmpty() ? "<none>" : memberFilter);
            }
        } else if (tab == Tab.SOURCE || tab == Tab.BYTECODE || tab == Tab.DEBUG) {
            editViewSearch();
        } else if (tab == Tab.REFERENCES || tab == Tab.STRINGS) {
            String value = editText("Find in current list", listSearch);
            if (value != null && !value.trim().isEmpty()) {
                listSearch = value.trim();
                findNextInView(1);
            }
        } else status = "Use / in Browse, Fields, Methods, References, String Hooks, Decompile, Bytecode, or Debug.";
    }

    private void editViewSearch() throws IOException {
        String prompt = tab == Tab.SOURCE ? "Find decompiled text"
                : "Find text, opcode:, operand:, bci:, line:, or string:";
        String value = editText(prompt, viewSearch);
        if (value == null || value.trim().isEmpty()) return;
        viewSearch = value.trim();
        findNextInView(1);
    }

    private void findNextInView(int direction) {
        if (tab == Tab.BROWSE || tab == Tab.FIELDS || tab == Tab.METHODS
                || tab == Tab.REFERENCES || tab == Tab.STRINGS) {
            if (listSearch.isEmpty()) { status = "Press f to find within the current list first."; return; }
            List<String> rows = new ArrayList<String>();
            if (tab == Tab.BROWSE) {
                for (TuiBrowserEntry entry : visibleBrowserEntries) rows.add(entry.displayName());
            } else if (tab == Tab.FIELDS) {
                if (unloadedContextClass != null) {
                    for (JvmClassPathCatalog.Member field : visibleUnloadedFields()) {
                        rows.add(unloadedFieldLabel(field));
                    }
                } else for (RemoteField field : visibleFields()) rows.add(fieldLabel(field));
            } else if (tab == Tab.METHODS) {
                if (unloadedContextClass != null) {
                    for (JvmClassPathCatalog.Member method : visibleUnloadedMethods()) {
                        rows.add(unloadedMethodLabel(method));
                    }
                } else for (RemoteMethod method : visibleMethods()) rows.add(methodLabel(method));
            } else if (tab == Tab.REFERENCES) {
                for (JvmReferenceInfo reference : session.references().snapshot()) {
                    rows.add(referenceLabel(reference));
                }
            } else {
                for (JvmStringHookInfo hook : session.stringHooks().snapshot()) {
                    rows.add(stringHookLabel(hook));
                }
            }
            int match = nextTextMatch(rows, selections[tab.ordinal()], listSearch, direction);
            if (match < 0) status = "No current-list match for: " + listSearch;
            else {
                selections[tab.ordinal()] = match;
                if (tab == Tab.METHODS && unloadedContextClass == null) {
                    selectedMethod = visibleMethods().get(match);
                }
                status = "Current-list match " + (match + 1) + "/" + rows.size()
                        + " (N previous, n next)";
            }
            return;
        }
        if (viewSearch.isEmpty() || (tab != Tab.SOURCE && tab != Tab.BYTECODE && tab != Tab.DEBUG)) {
            status = "Press / to enter a Decompile/Bytecode search first.";
            return;
        }
        if (tab == Tab.SOURCE) {
            int match = nextTextMatch(sourceLines, selections[tab.ordinal()], viewSearch, direction);
            if (match >= 0) {
                selections[tab.ordinal()] = match;
                status = "Decompile match at line " + (match + 1) + " (N previous, n next)";
            } else status = "No decompiled-text match for: " + viewSearch;
            return;
        }
        if (bytecode == null || bytecode.instructions().isEmpty()) {
            status = "Load bytecode before searching.";
            return;
        }
        String lower = viewSearch.toLowerCase(Locale.ROOT);
        String mode = "text";
        String expression = viewSearch;
        int separator = viewSearch.indexOf(':');
        if (separator > 0) {
            mode = viewSearch.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            expression = viewSearch.substring(separator + 1).trim();
        }
        debugSearchResults.clear();
        if ("string".equals(mode) || "constant".equals(mode)) {
            String needle = expression.toLowerCase(Locale.ROOT);
            for (String constant : constantPool) {
                if (constant.toLowerCase(Locale.ROOT).contains(needle)) {
                    debugSearchResults.add(constant);
                    if (debugSearchResults.size() >= 32) break;
                }
            }
        }
        List<BytecodeInstruction> instructions = bytecode.instructions();
        int start = selections[tab.ordinal()];
        for (int step = 1; step <= instructions.size(); step++) {
            int index = Math.floorMod(start + direction * step, instructions.size());
            BytecodeInstruction instruction = instructions.get(index);
            if (matchesInstruction(instruction, mode, expression)) {
                selections[tab.ordinal()] = index;
                status = "Bytecode match #" + index + " BCI " + instruction.offset()
                        + " (N previous, n next)";
                return;
            }
        }
        status = debugSearchResults.isEmpty() ? "No bytecode/constant match for: " + viewSearch
                : debugSearchResults.size() + " constant-pool match(es); see Debug inspector";
    }

    private static boolean matchesInstruction(
            BytecodeInstruction instruction, String mode, String expression) {
        String needle = expression.toLowerCase(Locale.ROOT);
        if ("bci".equals(mode)) return Long.toString(instruction.offset()).equals(expression);
        if ("line".equals(mode)) return Integer.toString(instruction.sourceLine()).equals(expression);
        if ("opcode".equals(mode)) return instruction.mnemonic().toLowerCase(Locale.ROOT).contains(needle);
        if ("operand".equals(mode) || "string".equals(mode) || "constant".equals(mode)) {
            return instruction.operands().toLowerCase(Locale.ROOT).contains(needle);
        }
        return instructionText(instruction).toLowerCase(Locale.ROOT).contains(
                (mode + ("text".equals(mode) ? "" : ":") + expression).toLowerCase(Locale.ROOT));
    }

    private static int nextTextMatch(List<String> lines, int start, String query, int direction) {
        if (lines.isEmpty()) return -1;
        String needle = query.toLowerCase(Locale.ROOT);
        for (int step = 1; step <= lines.size(); step++) {
            int index = Math.floorMod(start + direction * step, lines.size());
            if (lines.get(index).toLowerCase(Locale.ROOT).contains(needle)) return index;
        }
        return -1;
    }

    private void goToLocation() throws IOException {
        if (tab != Tab.SOURCE && tab != Tab.BYTECODE && tab != Tab.DEBUG) {
            status = "g jumps to a decompiled row, BCI, bytecode line, or instruction row.";
            return;
        }
        String value = editText(tab == Tab.SOURCE ? "Go to decompiled line"
                : "Go to row number, bci:<n>, or line:<n>", "");
        if (value == null || value.trim().isEmpty()) return;
        String query = value.trim().toLowerCase(Locale.ROOT);
        if (tab == Tab.SOURCE) {
            try {
                selections[tab.ordinal()] = clamp(Integer.parseInt(query) - 1,
                        0, Math.max(0, sourceLines.size() - 1));
                status = "Decompiled line " + (selections[tab.ordinal()] + 1);
            } catch (NumberFormatException failure) { status = "Invalid decompiled line: " + value; }
            return;
        }
        String mode = "row";
        int split = query.indexOf(':');
        if (split > 0) { mode = query.substring(0, split); query = query.substring(split + 1); }
        try {
            int wanted = Integer.parseInt(query);
            if ("row".equals(mode)) selections[tab.ordinal()] = clamp(wanted,
                    0, Math.max(0, itemCount(tab) - 1));
            else {
                viewSearch = mode + ":" + wanted;
                findNextInView(1);
            }
        } catch (NumberFormatException failure) { status = "Invalid location: " + value; }
    }

    private void goPackage() throws IOException {
        String value = editText("Go to exact package", packageName);
        if (value != null) requestPackage(value);
    }

    private void forceLoadClass(final boolean initialize) throws IOException {
        final String value = editText(initialize
                        ? "Class.forName in target JVM"
                        : "Load/link without <clinit> in target JVM",
                unloadedContextClass == null ? "" : unloadedContextClass.name());
        if (value == null) return;
        final String className = value.trim();
        if (className.isEmpty()) {
            status = "Class load cancelled: class name is empty.";
            return;
        }
        submit(initialize
                        ? "Loading " + className + " for Class.forName initialization..."
                        : "Loading/linking " + className + " without <clinit>...",
                new Callable<RemoteClass>() {
            @Override public RemoteClass call() {
                return initialize
                        ? session.startForceLoadClass(className)
                        : session.loadClassWithoutInitialization(className);
            }
        }, new Consumer<RemoteClass>() {
            @Override public void accept(RemoteClass type) {
                session.context().select(type);
                tab = Tab.CONTEXT;
                status = initialize
                        ? "Class.forName initialization started for " + type.className()
                                + "; <clinit> breakpoints can stop its loader thread"
                        : "Loaded and linked without <clinit>: " + type.className();
                requestContextRefresh();
            }
        });
    }

    private String editText(String prompt, String initial) throws IOException {
        if (tasks.userOperationBusy()) { status = busyMessage(); return null; }
        final String previousStatus = status;
        StringBuilder value = new StringBuilder(initial == null ? "" : initial);
        while (true) {
            status = prompt + ": " + value + "  (Enter apply, Esc/Ctrl+G cancel)";
            render();
            int key = screen.readKey();
            if (key == TuiKey.EOF) {
                status = previousStatus;
                return null;
            }
            if (key == TuiKey.ENTER) return value.toString();
            if (key == TuiKey.ESCAPE || key == TuiKey.CTRL_C || key == TuiKey.CTRL_G
                    || key == TuiKey.F10) {
                status = previousStatus;
                return null;
            }
            if (key == TuiKey.CTRL_U) {
                value.setLength(0);
                continue;
            }
            if (key == TuiKey.BACKSPACE || key == TuiKey.DELETE) {
                if (value.length() > 0) value.deleteCharAt(value.length() - 1);
            } else if (key >= 32 && key < 127) value.append((char) key);
        }
    }

    private void move(int delta) {
        int count = itemCount(tab);
        selections[tab.ordinal()] = clamp(selections[tab.ordinal()] + delta,
                0, Math.max(0, count - 1));
        if (tab == Tab.METHODS) {
            if (unloadedContextClass == null) {
                List<RemoteMethod> visible = visibleMethods();
                if (!visible.isEmpty()) selectedMethod = visible.get(selection());
            }
        }
    }

    private void moveToBoundary(boolean end) {
        selections[tab.ordinal()] = end ? Math.max(0, itemCount(tab) - 1) : 0;
        if (tab == Tab.METHODS) {
            if (unloadedContextClass == null) {
                List<RemoteMethod> visible = visibleMethods();
                if (!visible.isEmpty()) selectedMethod = visible.get(selection());
            }
        }
    }

    private void changeTab(int delta) {
        inspectorFocused = false;
        Tab[] tabs = Tab.values();
        tab = tabs[(tab.ordinal() + delta + tabs.length) % tabs.length];
        if (tab == Tab.DEBUG) alignDebuggerLocation(Tab.DEBUG);
        if (tab == Tab.REFERENCES && !tasks.busy()) requestReferenceRefresh();
        if ((tab == Tab.DEBUG || tab == Tab.FRAMES || tab == Tab.LOCALS || tab == Tab.THREADS)
                && !tasks.busy()) requestDebuggerRefresh();
        if (tab == Tab.STRINGS && !tasks.busy()) requestDebuggerRefresh();
    }

    private int itemCount(Tab value) {
        if (value == Tab.BROWSE) return visibleBrowserEntries.size();
        if (value == Tab.CONTEXT) return unloadedContextClass != null ? 1
                : session.context().isSet() ? session.context().depth() : 0;
        if (value == Tab.REFERENCES) return session.references().snapshot().size();
        if (value == Tab.FIELDS) return unloadedContextClass != null
                ? visibleUnloadedFields().size() : visibleFields().size();
        if (value == Tab.METHODS) return unloadedContextClass != null
                ? visibleUnloadedMethods().size() : visibleMethods().size();
        if (value == Tab.FRAMES) return debuggerFrames.size();
        if (value == Tab.LOCALS) return debuggerLocals.size();
        if (value == Tab.BREAKPOINTS) return breakpoints.size();
        if (value == Tab.THREADS) return debuggerThreads.size();
        if (value == Tab.STRINGS) return session.stringHooks().snapshot().size();
        if (value == Tab.BYTECODE || value == Tab.DEBUG) {
            return bytecode == null ? 0 : bytecode.instructions().size();
        }
        return sourceLines.size();
    }

    private int selection() { return selections[tab.ordinal()]; }

    private int bytecodeCursor() {
        return clamp(selections[tab.ordinal()], 0,
                Math.max(0, bytecode == null ? 0 : bytecode.instructions().size() - 1));
    }

    private List<RemoteField> visibleFields() {
        List<RemoteField> result = new ArrayList<RemoteField>();
        String needle = memberFilter.toLowerCase(Locale.ROOT);
        for (RemoteField field : fields) {
            if (!showStaticMembers && field.isStatic()) continue;
            if (!showVirtualMembers && !field.isStatic()) continue;
            if (needle.isEmpty() || field.name().toLowerCase(Locale.ROOT).contains(needle)
                    || field.typeName().toLowerCase(Locale.ROOT).contains(needle)) result.add(field);
        }
        return result;
    }

    private List<RemoteMethod> visibleMethods() {
        List<RemoteMethod> result = new ArrayList<RemoteMethod>();
        String needle = memberFilter.toLowerCase(Locale.ROOT);
        for (RemoteMethod method : methods) {
            if (!showStaticMembers && method.isStatic()) continue;
            if (!showVirtualMembers && !method.isStatic()) continue;
            if (TuiBrowserModel.inheritedObjectMethodHidden(
                    contextClass == null ? null : contextClass.className(),
                    method.declaringClass(), hideInheritedObjectMethods)) continue;
            if (needle.isEmpty() || method.name().toLowerCase(Locale.ROOT).contains(needle)
                    || method.returnTypeName().toLowerCase(Locale.ROOT).contains(needle)
                    || method.descriptor().toLowerCase(Locale.ROOT).contains(needle)) result.add(method);
        }
        return result;
    }

    private List<JvmClassPathCatalog.Member> visibleUnloadedFields() {
        return visibleUnloadedMembers(unloadedFields, JvmClassPathCatalog.MemberKind.FIELD);
    }

    private List<JvmClassPathCatalog.Member> visibleUnloadedMethods() {
        return visibleUnloadedMembers(unloadedMethods, JvmClassPathCatalog.MemberKind.METHOD);
    }

    private List<JvmClassPathCatalog.Member> visibleUnloadedMembers(
            List<JvmClassPathCatalog.Member> source, JvmClassPathCatalog.MemberKind kind) {
        List<JvmClassPathCatalog.Member> result = new ArrayList<JvmClassPathCatalog.Member>();
        String needle = memberFilter.toLowerCase(Locale.ROOT);
        for (JvmClassPathCatalog.Member member : source) {
            if (member.kind() != kind) continue;
            if (!showStaticMembers && member.isStatic()) continue;
            if (!showVirtualMembers && !member.isStatic()) continue;
            if (kind == JvmClassPathCatalog.MemberKind.METHOD && !showSpecialMethods
                    && ("<init>".equals(member.name()) || "<clinit>".equals(member.name()))) continue;
            if (needle.isEmpty() || member.name().toLowerCase(Locale.ROOT).contains(needle)
                    || member.typeSummary().toLowerCase(Locale.ROOT).contains(needle)
                    || member.descriptor().toLowerCase(Locale.ROOT).contains(needle)) result.add(member);
        }
        return result;
    }

    private JvmClassPathCatalog.Member selectedUnloadedField() {
        List<JvmClassPathCatalog.Member> visible = visibleUnloadedFields();
        return visible.isEmpty() ? null : visible.get(clamp(
                selections[Tab.FIELDS.ordinal()], 0, visible.size() - 1));
    }

    private JvmClassPathCatalog.Member selectedUnloadedMethod() {
        List<JvmClassPathCatalog.Member> visible = visibleUnloadedMethods();
        return visible.isEmpty() ? null : visible.get(clamp(
                selections[Tab.METHODS.ordinal()], 0, visible.size() - 1));
    }

    private void clearUnloadedContext() {
        unloadedContextClass = null;
        unloadedFields.clear();
        unloadedMethods.clear();
    }

    private void clampMemberSelections() {
        int fieldCount = unloadedContextClass != null
                ? visibleUnloadedFields().size() : visibleFields().size();
        int methodCount = unloadedContextClass != null
                ? visibleUnloadedMethods().size() : visibleMethods().size();
        selections[Tab.FIELDS.ordinal()] = clamp(selections[Tab.FIELDS.ordinal()],
                0, Math.max(0, fieldCount - 1));
        selections[Tab.METHODS.ordinal()] = clamp(selections[Tab.METHODS.ordinal()],
                0, Math.max(0, methodCount - 1));
    }

    private boolean requireContext() {
        if (contextClass != null && session.context().isSet()) return true;
        status = "Select a class or object context from Browse first.";
        tab = Tab.BROWSE;
        return false;
    }

    private <T> boolean submit(String label, Callable<T> operation, Consumer<T> success) {
        boolean accepted = tasks.submitOrQueue(label, operation, success, new Consumer<Throwable>() {
            @Override public void accept(Throwable failure) { recordError(failure); }
        });
        status = accepted ? label : "Busy: " + busyMessage();
        return accepted;
    }

    private String busyMessage() {
        String activity = tasks.activity();
        return activity.isEmpty() ? "Refreshing debugger state; retry in a moment." : activity;
    }

    private void recordError(Throwable failure) {
        pendingMemberResult = null;
        String message = rootMessage(failure);
        if (errors.size() >= 8) errors.removeFirst();
        errors.addLast(message);
        status = "ERROR: " + message;
    }

    private void render() {
        // Never paint the physical last terminal column. Windows Terminal enables
        // delayed autowrap when that cell is written, which appears as a flashing
        // reverse-video block at the right edge during frequent debugger refreshes.
        int width = Math.max(1, screen.width() - 1);
        int height = Math.max(1, screen.height());
        List<String> output = new ArrayList<String>();
        output.add(TerminalScreen.REVERSE + TerminalScreen.pad(title(width), width) + TerminalScreen.RESET);
        boolean tabsVisible = height >= 5;
        boolean statusVisible = height >= 3;
        boolean helpVisible = height >= 6;
        List<String> shortcuts = helpVisible
                ? TuiFooter.allRows(helpTokens(), width)
                : Collections.<String>emptyList();
        if (tabsVisible) output.add(tabsLine(width));
        List<String> statusRows = Collections.emptyList();
        if (statusVisible) {
            String taskActivity = tasks.activity();
            String activity = taskActivity.isEmpty() ? status : taskActivity;
            if (horizontalOffsets[tab.ordinal()] > 0) {
                activity += " | x=" + horizontalOffsets[tab.ordinal()] + "  [ / ] scroll  0 reset";
            }
            if (!activity.equals(pagedStatus)) {
                pagedStatus = activity;
                statusPage = 0;
            }
            int maximumStatusRows = Math.max(1,
                    height - output.size() - shortcuts.size() - 1);
            statusRows = TuiFooter.statusRows(
                    activity, width, maximumStatusRows, statusPage);
        }
        int reservedFooterRows = statusRows.size() + shortcuts.size();
        int bodyRows = Math.max(0, height - output.size() - reservedFooterRows);
        if (bodyRows > 0) {
            if (tab == Tab.SOURCE) renderSource(output, width, bodyRows);
            else if (tab == Tab.BYTECODE || tab == Tab.DEBUG) renderWorkbench(output, width, bodyRows);
            else renderBrowserPane(output, width, bodyRows);
        }
        for (String statusRow : statusRows) {
            output.add(TerminalScreen.REVERSE
                    + TerminalScreen.pad(statusRow, width) + TerminalScreen.RESET);
        }
        for (String shortcut : shortcuts) output.add(TerminalScreen.pad(shortcut, width));
        screen.draw(output);
    }

    private String title(int width) {
        String context = unloadedContextClass != null ? "unloaded class " + unloadedContextClass.name()
                : session.context().isSet() ? session.context().description() : "<no context>";
        if (width < 90) {
            return " JVMRTDP " + session.server().process().pid() + " | "
                    + tab.name().toLowerCase(Locale.ROOT) + " | " + context;
        }
        String location = tab == Tab.SOURCE ? "decompile:" + sourceTitle
                : (tab == Tab.BYTECODE || tab == Tab.DEBUG) && !bytecodeClass.isEmpty()
                        ? "method:" + bytecodeClass + "." + bytecodeMethod : browserTitle;
        return " JVMRTDP " + session.server().process().pid() + " | CTX " + context
                + " | " + location + " | " + engine + " ";
    }

    private String tabsLine(int width) {
        StringBuilder result = new StringBuilder();
        for (Tab value : Tab.values()) {
            String name = width < 116 ? shortTabName(value) : displayTabName(value);
            String label = " " + name + " ";
            result.append(value == tab ? TerminalScreen.REVERSE + label + TerminalScreen.RESET : label);
        }
        return result.toString();
    }

    private static String displayTabName(Tab value) {
        return value == Tab.SOURCE ? "decompile" : value.name().toLowerCase(Locale.ROOT);
    }

    private void renderBrowserPane(List<String> output, int width, int bodyRows) {
        List<String> left = leftLines();
        List<String> right = rightLines();
        if (width < 90) {
            List<String> compact = left;
            int selected = selection();
            int scroll = scrolls[tab.ordinal()];
            boolean selectable = selectableTab();
            if (selectable) {
                if (selected < scroll) scroll = selected;
                if (selected >= scroll + bodyRows) scroll = selected - bodyRows + 1;
                scrolls[tab.ordinal()] = Math.max(0, scroll);
            }
            for (int row = 0; row < bodyRows; row++) {
                int index = scroll + row;
                String text = index < compact.size() ? compact.get(index) : "";
                String cell = TerminalScreen.pad(TuiViewport.horizontal(text,
                        horizontalOffsets[tab.ordinal()], width), width);
                if (selectable && index == selected && index < left.size()) {
                    cell = TerminalScreen.REVERSE + cell + TerminalScreen.RESET;
                }
                output.add(cell);
            }
            return;
        }
        int leftWidth = clamp(width * 38 / 100, 28, 54);
        int rightWidth = Math.max(1, width - leftWidth - 3);
        int selected = selection();
        int scroll = scrolls[tab.ordinal()];
        if (selectableTab()) {
            if (selected < scroll) scroll = selected;
            if (selected >= scroll + bodyRows) scroll = selected - bodyRows + 1;
            scrolls[tab.ordinal()] = scroll;
        } else scroll = scrolls[tab.ordinal()];
        for (int row = 0; row < bodyRows; row++) {
            int leftIndex = selectableTab() ? scroll + row : row;
            String leftText = leftIndex < left.size() ? left.get(leftIndex) : "";
            String leftCell = TerminalScreen.pad(TuiViewport.horizontal(leftText,
                    horizontalOffsets[tab.ordinal()], leftWidth), leftWidth);
            if (selectableTab() && leftIndex == selected) {
                leftCell = TerminalScreen.REVERSE + leftCell + TerminalScreen.RESET;
            }
            int rightIndex = tab == Tab.SOURCE ? scrolls[tab.ordinal()] + row : row;
            String rightText = rightIndex < right.size() ? right.get(rightIndex) : "";
            output.add(leftCell + " | " + TerminalScreen.pad(TuiViewport.horizontal(rightText,
                    horizontalOffsets[tab.ordinal()], rightWidth), rightWidth));
        }
    }

    private void renderSource(List<String> output, int width, int bodyRows) {
        int cursor = clamp(selections[Tab.SOURCE.ordinal()], 0, Math.max(0, sourceLines.size() - 1));
        int executionLine = sourceExecutionLine();
        int vertical = clamp(scrolls[Tab.SOURCE.ordinal()], 0, Math.max(0, sourceLines.size() - 1));
        if (cursor < vertical) vertical = cursor;
        if (cursor >= vertical + bodyRows) vertical = cursor - bodyRows + 1;
        scrolls[Tab.SOURCE.ordinal()] = vertical;
        int digits = Math.max(3, Integer.toString(Math.max(1, sourceLines.size())).length());
        int gutterWidth = Math.min(width, digits + 2);
        int contentWidth = Math.max(0, width - gutterWidth - (width > gutterWidth ? 1 : 0));
        int horizontal = horizontalOffsets[Tab.SOURCE.ordinal()];
        for (int row = 0; row < bodyRows; row++) {
            int index = vertical + row;
            boolean executing = index == executionLine && index < sourceLines.size();
            String gutter = index < sourceLines.size()
                    ? String.format("%s%" + digits + "d ", executing ? ">" : " ", index + 1) : "";
            String source = index < sourceLines.size()
                    ? TuiViewport.horizontal(sourceLines.get(index), horizontal, contentWidth) : "";
            String line = TerminalScreen.DIM + TerminalScreen.pad(gutter, gutterWidth) + TerminalScreen.RESET;
            if (contentWidth > 0) line += " " + TerminalScreen.pad(source, contentWidth);
            if (index < sourceLines.size() && (executing || index == cursor)) {
                String style = (executing ? TerminalScreen.BOLD + TerminalScreen.YELLOW : "")
                        + (index == cursor ? TerminalScreen.REVERSE : "");
                line = style + line + TerminalScreen.RESET;
            }
            output.add(line);
        }
    }

    private boolean selectableTab() {
        return tab == Tab.BROWSE || tab == Tab.CONTEXT || tab == Tab.FIELDS
                || tab == Tab.METHODS || tab == Tab.FRAMES || tab == Tab.LOCALS
                || tab == Tab.BREAKPOINTS || tab == Tab.THREADS
                || tab == Tab.REFERENCES || tab == Tab.STRINGS;
    }

    private List<String> leftLines() {
        List<String> result = new ArrayList<String>();
        if (tab == Tab.BROWSE) {
            for (TuiBrowserEntry entry : visibleBrowserEntries) result.add(entry.displayName());
        } else if (tab == Tab.CONTEXT || tab == Tab.SOURCE) {
            if (unloadedContextClass != null && tab == Tab.CONTEXT) {
                result.add("#0 [U:C] " + unloadedContextClass.name());
            } else if (session.context().isSet()) {
                List<String> stack = session.context().stack(128);
                for (int index = 0; index < stack.size(); index++) result.add("#" + index + " " + stack.get(index));
            }
        } else if (tab == Tab.FIELDS) {
            if (unloadedContextClass != null) {
                for (JvmClassPathCatalog.Member field : visibleUnloadedFields()) {
                    result.add(unloadedFieldLabel(field));
                }
            } else for (RemoteField field : visibleFields()) result.add(fieldLabel(field));
        } else if (tab == Tab.REFERENCES) {
            for (JvmReferenceInfo reference : session.references().snapshot()) {
                result.add(referenceLabel(reference));
            }
        } else if (tab == Tab.METHODS) {
            if (unloadedContextClass != null) {
                for (JvmClassPathCatalog.Member method : visibleUnloadedMethods()) {
                    result.add(unloadedMethodLabel(method));
                }
            } else for (RemoteMethod method : visibleMethods()) result.add(methodLabel(method));
        } else if (tab == Tab.FRAMES) {
            for (JvmStackFrame frame : debuggerFrames) {
                result.add((frame.depth() == debuggerFrameDepth ? ">" : " ")
                        + (frame.depth() == 0 ? "@ " : "  ") + frame.display());
            }
        } else if (tab == Tab.LOCALS) {
            for (JvmDebuggerLocal local : debuggerLocals) result.add(localLabel(local));
        } else if (tab == Tab.BREAKPOINTS) {
            for (BreakpointSpec breakpoint : breakpoints.values()) result.add(breakpointLabel(breakpoint));
        } else if (tab == Tab.THREADS) {
            for (RemoteJvmtiThread thread : debuggerThreads) {
                result.add(debuggerThreadLabel(thread));
            }
        } else if (tab == Tab.STRINGS) {
            for (JvmStringHookInfo hook : session.stringHooks().snapshot()) {
                result.add(stringHookLabel(hook));
            }
        }
        return result;
    }

    private List<String> rightLines() {
        if (tab == Tab.CONTEXT) {
            List<String> result = new ArrayList<String>(contextLines);
            if (unloadedContextClass != null) {
                result.add("");
                result.add("OFFLINE CONTEXT NAVIGATION");
                addKeyHelp(result, "Tab / Shift+Tab", "Browse Fields and Methods like a loaded class");
                addKeyHelp(result, "A", "Decompile this class without loading it");
                addKeyHelp(result, "l", "Load/initialize this class with Class.forName");
                addKeyHelp(result, "L", "Load/link this class without running <clinit>");
                addKeyHelp(result, "Backspace", "Return to the unloaded package browser");
                return result;
            }
            result.add("");
            result.add("WRITE SOURCE");
            result.add(session.context().canAssign()
                    ? session.context().assignmentDescription() + "  (= writes a replacement)"
                    : "<read-only snapshot; select a field/array element/paused local to make it writable>");
            result.add("");
            result.add("STACK MANAGEMENT");
            addKeyHelp(result, "Enter", "Move selected item to top");
            addKeyHelp(result, "Space", "Copy selected item to top");
            addKeyHelp(result, "S", "Swap top two items");
            addKeyHelp(result, "Delete", "Remove selected item");
            addKeyHelp(result, "X", "Clear context stack");
            return result;
        }
        if (tab == Tab.REFERENCES) {
            List<String> result = new ArrayList<String>();
            JvmReferenceInfo reference = selectedReference();
            result.add("TRACKED REFERENCES");
            if (reference == null) {
                result.add("<none>");
                result.add("");
                result.add("Select an object Context, then press S (strong) or Shift+S (weak).");
                result.add("In Fields, & tracks a live field slot instead of a one-time snapshot.");
                return result;
            }
            result.add("Name:       " + reference.name());
            result.add("State:      " + reference.state());
            result.add("Strength:   " + reference.strength());
            result.add("Kind:       " + reference.kind());
            result.add("Source:     " + reference.source());
            result.add("Assignable: " + reference.assignable());
            result.add("Remote ID:  " + (reference.remoteId() == 0L ? "<none>" : reference.remoteId()));
            result.add("Type:       " + (reference.className().isEmpty() ? "<unavailable>" : reference.className()));
            result.add("Value:      " + (reference.displayValue().isEmpty() ? "<unavailable>" : reference.displayValue()));
            if (!reference.error().isEmpty()) result.add("Error:      " + reference.error());
            result.add("");
            result.add("LIVE means accessible; NULL is Java null; COLLECTED is a reclaimed weak object.");
            addKeyHelp(result, "Enter", "Acquire a strong handle and set it as Context");
            addKeyHelp(result, "S / Shift+S", "Save current Context strongly / weakly");
            addKeyHelp(result, "=", "Replace this object slot or tracked field value");
            addKeyHelp(result, "X", "Set the tracked slot/field to Java null");
            addKeyHelp(result, "Delete", "Release this tracked reference");
            addKeyHelp(result, "F5", "Refresh liveness and live field values");
            return result;
        }
        if (tab == Tab.STRINGS) {
            List<String> result = new ArrayList<String>();
            JvmStringHookInfo hook = selectedStringHook();
            result.add("STRING HOOKS");
            if (hook == null) {
                result.add("<none>");
                result.add("");
                result.add("A adds an allocation, field read/write, or method entry/exit hook.");
                result.add("Select a java.lang.String field in Fields and press ; for a guided hook.");
                return result;
            }
            result.add("Name:       " + hook.name());
            result.add("State:      " + (hook.enabled() ? "ENABLED" : "disabled"));
            result.add("Kind:       " + hook.kind());
            result.add("Target:     " + hook.className() + "." + hook.memberName());
            result.add("Descriptor: " + hook.descriptor());
            result.add("Scope:      " + (hook.allocationHook() ? "all new matching Strings"
                    : hook.objectSpecific() ? "current object only" : "all matching instances"));
            if (hook.allocationSpec() != null) {
                result.add("Content:    " + hook.allocationSpec().contentPattern());
                result.add("Creator:    " + hook.allocationSpec().creatorClassPattern() + "#"
                        + hook.allocationSpec().creatorMethodPattern()
                        + hook.allocationSpec().creatorDescriptorPattern());
                result.add("Case:       " + (hook.allocationSpec().caseSensitive()
                        ? "sensitive" : "insensitive"));
                result.add("Hits:       " + hook.hitCount());
                result.add("Last value: " + (hook.lastValue().isEmpty()
                        ? "<none>" : hook.lastValue()));
            }
            result.add("Last hit:   " + (hook.lastHit().isEmpty() ? "<none>" : hook.lastHit()));
            result.add("");
            result.add("Field hooks can read, replace, track, or open their current String value.");
            result.add("Allocation hooks stop matching creators and retain the latest matched String.");
            result.add("Method hook hits pause in Debug; Locals/Frames expose arguments and return values.");
            addKeyHelp(result, "A", "Add a String allocation, field, or method hook");
            addKeyHelp(result, "Enter", hook.fieldHook() || hook.allocationHook()
                    ? "Open current/matched String as Context" : "Open last hit in Debug");
            addKeyHelp(result, "F9", "Enable or disable this hook");
            addKeyHelp(result, "=", "Replace a field-backed String value");
            addKeyHelp(result, "&", "Track a field/allocation String in References");
            addKeyHelp(result, "Delete", "Remove this hook");
            return result;
        }
        if (tab == Tab.SOURCE) return sourceLines;
        List<String> result = new ArrayList<String>();
        if (tab == Tab.BROWSE && !visibleBrowserEntries.isEmpty()) {
            TuiBrowserEntry entry = visibleBrowserEntries.get(selection());
            result.add(entry.kind() == TuiBrowserEntry.Kind.CLASS ? "Class: " + entry.name()
                    : entry.kind() == TuiBrowserEntry.Kind.PARENT ? "Parent package: " + entry.name()
                    : entry.kind() == TuiBrowserEntry.Kind.PACKAGE ? "Package: " + entry.name()
                    : entry.kind() == TuiBrowserEntry.Kind.FIELD ? "Field: " + entry.name()
                    : "Method: " + entry.name());
            if (entry.unloaded()) {
                result.add("State: UNLOADED (catalog metadata only)");
                if (entry.unloadedClass() != null) {
                    result.add("Origin: " + entry.unloadedClass().origin());
                }
                result.add("ClassPrepare installs registered breakpoints/watchpoints automatically.");
            }
            if (entry.kind() == TuiBrowserEntry.Kind.CLASS && entry.name().startsWith("[")) {
                result.add("Kind: array class");
                result.add("Initialization: arrays have no <init>/<clinit> and no classfile Code attribute");
                result.add("To hook class initialization, select the component/owner class instead.");
            }
            RemoteClassInfo info = entry.classInfo();
            if (info != null) {
                result.add("Kind: " + (info.isInterface() ? "interface" : info.isEnum() ? "enum"
                        : info.isArray() ? "array" : "class"));
                result.add("Super: " + (info.superclass().isEmpty() ? "<none>" : info.superclass()));
                result.add("Interfaces: " + info.interfaces());
            }
            if (entry.field() != null) {
                RemoteField field = entry.field();
                result.add("Owner:      " + field.declaringClass());
                result.add("Type:       " + field.typeName());
                result.add("Descriptor: " + field.descriptor());
                result.add("Modifiers:  " + Modifier.toString(field.modifiers()));
            } else if (entry.method() != null) {
                RemoteMethod method = entry.method();
                result.add("Owner:          " + method.declaringClass());
                result.add("Return:         " + method.returnTypeName());
                result.add("Parameters:     " + method.parameterTypeNames());
                result.add("Descriptor:     " + method.descriptor());
                result.add("Implementation: " + method.implementationKind());
                result.add("Modifiers:      " + Modifier.toString(method.modifiers()));
            } else if (entry.unloadedMember() != null) {
                JvmClassPathCatalog.Member member = entry.unloadedMember();
                result.add("Owner:          " + entry.ownerName());
                result.add("Descriptor:     " + member.descriptor());
                result.add("Type:           " + member.typeSummary());
                result.add("Implementation: " + (member.isNative() ? "NATIVE"
                        : member.isAbstract() ? "ABSTRACT" : "BYTECODE"));
                result.add("Modifiers:      " + Modifier.toString(member.access()));
            }
            result.add("");
            result.add("SHORTCUTS");
            addKeyHelp(result, "Enter", entry.unloaded() && entry.kind() == TuiBrowserEntry.Kind.CLASS
                    ? "Browse this unloaded class' fields and methods"
                    : entry.unloaded() && entry.kind() == TuiBrowserEntry.Kind.METHOD
                            ? "Open bytecode without loading the class"
                    : entry.kind() == TuiBrowserEntry.Kind.CLASS
                    ? "Select this CLASS metadata context"
                    : entry.kind() == TuiBrowserEntry.Kind.PACKAGE
                            || entry.kind() == TuiBrowserEntry.Kind.PARENT
                            ? "Open this package"
                            : "Open declaring class and select member");
            addKeyHelp(result, "Backspace", "Open parent package/context");
            addKeyHelp(result, "f", "Find only in this displayed list");
            addKeyHelp(result, "F", browseUnloaded
                    ? "Search unloaded class-path classes/members"
                    : "Search all loaded classes/members/packages");
            addKeyHelp(result, ":", "Open an exact class/package/member target");
            addKeyHelp(result, "l", "Class.forName and initialize a target class");
            addKeyHelp(result, "L", "Load/link a target class without running <clinit>");
            addKeyHelp(result, "U", browseUnloaded
                    ? "Return to loaded classes"
                    : "Open separate unloaded class-path catalog");
            if (entry.unloaded() && entry.kind() == TuiBrowserEntry.Kind.METHOD) {
                addKeyHelp(result, "F9", "Register pending BCI 0 breakpoint");
                addKeyHelp(result, "Ctrl+E/X", "Register method entry/exit event breakpoint");
                addKeyHelp(result, "B / S", "Open bytecode / decompile without loading");
            } else if (entry.unloaded() && entry.kind() == TuiBrowserEntry.Kind.FIELD) {
                addKeyHelp(result, "u / W", "Register pending field read / write watch");
            }
            addKeyHelp(result, "/", "Filter the current list");
            addKeyHelp(result, "J", "Show/hide JDK runtime entries");
            addKeyHelp(result, "[ / ]", "Scroll clipped text horizontally");
            addKeyHelp(result, "0", "Reset horizontal position");
        } else if (tab == Tab.FIELDS) {
            if (unloadedContextClass != null) return unloadedFieldInfo();
            List<RemoteField> visible = visibleFields();
            result.add("Static fields: " + (showStaticMembers ? "visible" : "hidden")
                    + "  (@ toggles)");
            result.add("Instance fields: " + (showVirtualMembers ? "visible" : "hidden")
                    + "  (# toggles)");
            result.add("");
            if (visible.isEmpty()) {
                result.add(fields.isEmpty() ? "<no fields were returned for this context>"
                        : "No field matches the active filters. Press @, #, or / to adjust them.");
                result.add("Press F for global field search or : to enter owner#field directly.");
            } else {
                RemoteField field = visible.get(selection());
                result.add((field.isStatic() ? "STATIC " : "INSTANCE ")
                        + Modifier.toString(field.modifiers()) + " " + field.typeName() + " " + field.name());
                result.add("Declared by: " + field.declaringClass());
                result.add("Descriptor:  " + field.descriptor());
                result.add("");
                if (!field.isStatic() && classContext) {
                    result.add("Metadata-only virtual field: no object reference is required to find/watch it.");
                    result.add("An OBJECT context is required only to read its value.");
                } else {
                    result.add("Reading pushes the result onto the shared context stack.");
                }
                result.add("");
                result.add("SHORTCUTS");
                addKeyHelp(result, "Enter", "Read field value");
                addKeyHelp(result, "=", "Set this field from a literal/reference/expression");
                addKeyHelp(result, "@", "Show/hide static fields");
                addKeyHelp(result, "#", "Show/hide instance fields");
                addKeyHelp(result, "f / F", "Find in list / search globally");
                addKeyHelp(result, "U", "Toggle field-read watchpoint");
                addKeyHelp(result, "W", "Toggle field-write watchpoint");
                addKeyHelp(result, "[ / ]", "Scroll clipped text horizontally");
                addKeyHelp(result, "0", "Reset horizontal position");
            }
        } else if (tab == Tab.METHODS) {
            if (unloadedContextClass != null) return unloadedMethodInfo();
            List<RemoteMethod> visible = visibleMethods();
            result.add("Static methods: " + (showStaticMembers ? "visible" : "hidden")
                    + "  (@ toggles)");
            result.add("Virtual methods: " + (showVirtualMembers ? "visible" : "hidden")
                    + "  (# toggles)");
            result.add("Object inheritance: " + (hideInheritedObjectMethods
                    ? "hidden when not overridden" : "visible") + " (H toggles)");
            result.add("");
            if (visible.isEmpty()) {
                result.add(methods.isEmpty() ? "<no methods were returned for this context>"
                        : "No method matches the active member/Object/text filters. Press @, #, H, or /.");
                result.add("Press F for global method search or : to enter owner#method directly.");
            } else {
                RemoteMethod method = visible.get(selection());
                result.add((method.isStatic() ? "STATIC " : "INSTANCE ")
                        + Modifier.toString(method.modifiers()) + " " + method.returnTypeName()
                        + " " + method.name() + method.parameterTypeNames());
                result.add("Declared by: " + method.declaringClass());
                result.add("Descriptor:  " + method.descriptor());
                result.add("Implementation: " + method.implementationKind());
                if (method.isJvmSpecial()) {
                    result.add("JVM lifecycle: " + ("<init>".equals(method.name())
                            ? "instance constructor" : "class initialization"));
                    result.add("Not a reflective call target; bytecode/debug actions remain available.");
                    if ("<clinit>".equals(method.name())) {
                        result.add("Note: <clinit> runs once; an already initialized class cannot hit it again.");
                        result.add("Hook init at BCI 0 before initialization starts.");
                    }
                }
                if (method.isNative()) result.add("Code: native implementation; no JVM Code attribute");
                else if (method.isAbstract()) result.add("Code: abstract declaration; no JVM Code attribute");
                else result.add("Code: JVM bytecode is available");
                if (contextClass != null && !method.declaringClass().equals(contextClass.className())) {
                    result.add("Inherited: bytecode/decompilation uses the declaring class above.");
                }
                result.add("");
                result.add("SHORTCUTS");
                addKeyHelp(result, "H", "Hide/show unoverridden Object methods");
                addKeyHelp(result, "K", "Show/hide <init> and <clinit>");
                addKeyHelp(result, "Enter / B", "Open method bytecode");
                addKeyHelp(result, "x / X", "Invoke virtually / exact declaring implementation");
                addKeyHelp(result, "@", "Show/hide static methods");
                addKeyHelp(result, "#", "Show/hide virtual methods");
                addKeyHelp(result, "f / F", "Find in list / search globally");
                addKeyHelp(result, "F9", "Toggle entry breakpoint at BCI 0");
                addKeyHelp(result, "S", "Decompile selected method");
                addKeyHelp(result, "A", "Decompile context class");
                addKeyHelp(result, "[ / ]", "Scroll clipped text horizontally");
                addKeyHelp(result, "0", "Reset horizontal position");
            }
        } else if (tab == Tab.FRAMES) {
            result.add("STACK FRAMES (" + debuggerFrames.size() + ")");
            result.add("@ = actual suspension/sample point; > = selected inspection frame");
            if ((debuggerState == null || !debuggerState.paused()) && !liveSampleAvailable) {
                result.add("No paused thread or live-follow sample is available.");
            } else if (debuggerFrames.isEmpty()) {
                result.add("<stack unavailable>");
            } else {
                JvmStackFrame frame = debuggerFrames.get(selection());
                result.add("Depth:      " + frame.depth());
                result.add("Class:      " + frame.className());
                result.add("Method:     " + frame.methodName());
                result.add("Descriptor: " + frame.descriptor());
                result.add("Location:   " + (frame.hasJavaLocation()
                        ? "BCI " + frame.location() : "native (no Java BCI)"));
                result.add("Kind:       " + (frame.isNative() ? "NATIVE" : "JAVA BYTECODE"));
                result.add("");
                if (frame.depth() == 0) {
                    result.add(liveSampleAvailable && (debuggerState == null || !debuggerState.paused())
                            ? "This was the thread's actual top at sample time."
                            : "This is the thread's actual suspension point.");
                } else {
                    result.add("This caller frame is inspectable; the actual top is frame #0.");
                    result.add("Already executed bytecodes cannot be stepped backwards.");
                }
                if (frame.isNative()) {
                    result.add("Select the nearest Java/application frame below this native frame.");
                }
                result.add("");
                result.add("SHORTCUTS");
                addKeyHelp(result, "Enter", "Open this frame's bytecode and BCI");
                addKeyHelp(result, "B", "Open this frame in Bytecode at its BCI");
                addKeyHelp(result, "S", "Decompile this frame and highlight its BCI line");
                addKeyHelp(result, "G", "Jump to this frame's current execution point");
                addKeyHelp(result, "M", "Read locals for this paused/live-sampled frame");
                addKeyHelp(result, "F9", "Set a persistent breakpoint after opening bytecode");
                addKeyHelp(result, "T", "Return to all JVM threads");
            }
        } else if (tab == Tab.LOCALS) {
            result.add("FRAME " + debuggerFrameDepth + " LOCALS");
            if ((debuggerState == null || !debuggerState.paused()) && !liveSampleAvailable) {
                result.add("No paused thread or live-follow sample is available.");
                result.add("Enable F4 live follow, pause a thread, or wait for a breakpoint.");
            } else if (debuggerLocals.isEmpty()) {
                result.add(debuggerLocalsError.isEmpty() ? "<no readable locals>" : debuggerLocalsError);
            } else {
                JvmDebuggerLocal local = debuggerLocals.get(selection());
                result.add("Name:       " + local.name());
                result.add("Slot:       " + local.slot());
                result.add("Descriptor: " + local.descriptor());
                result.add("Scope:      BCI " + local.scopeStart() + ".."
                        + (local.scopeStart() + local.scopeLength()));
                result.add("Source:     " + (local.inferred() ? "inferred from maxLocals" : "LocalVariableTable"));
                result.add("Value:      " + (local.available()
                        ? local.value() == null ? "null" : local.value().displayValue()
                        : "<" + local.error() + ">"));
                result.add("");
                result.add("SHORTCUTS");
                addKeyHelp(result, "Enter", "Push this local value onto Context");
                addKeyHelp(result, "=", debuggerState != null && debuggerState.paused()
                        ? "Set this paused local" : "Live-sampled locals are read-only");
                addKeyHelp(result, "G", "Return to selected execution frame BCI");
                addKeyHelp(result, "Tab", "Frames selects another stack depth");
                addKeyHelp(result, "T", "Open JVM threads");
            }
        } else if (tab == Tab.BREAKPOINTS) {
            result.add("MANAGED BREAKPOINTS (" + breakpoints.size() + ")");
            List<BreakpointSpec> values = breakpointList();
            if (values.isEmpty()) result.add("<none>");
            else {
                BreakpointSpec breakpoint = values.get(selection());
                result.add("Class:      " + breakpoint.className);
                result.add("Method:     " + breakpoint.methodName);
                result.add("Descriptor: " + breakpoint.descriptor);
                result.add("BCI:        " + breakpoint.bci);
                result.add("Line:       " + (breakpoint.line < 0 ? "<unknown>" : breakpoint.line));
                result.add("Scope:      " + (breakpoint.receiverId == 0L
                        ? "all instances" : "current object"));
                result.add("State:      enabled"
                        + (isCurrentBreakpoint(breakpoint) ? " / CURRENT STOP" : ""));
                result.add("");
                result.add("SHORTCUTS");
                addKeyHelp(result, "Enter", "Open bytecode at this breakpoint");
                addKeyHelp(result, "F9 / Delete", "Clear selected breakpoint");
                addKeyHelp(result, "A", "Clear all managed breakpoints");
            }
        } else if (tab == Tab.THREADS) {
            RemoteJvmtiThread thread = selectedDebuggerThread();
            result.add("ALL JVM THREADS (" + debuggerThreads.size() + ")");
            result.add("ANALYSIS FREEZE: " + (session.debugger().active()
                    ? "ACTIVE (" + session.debugger().ownedThreadCount() + " owned)"
                    : "off") + "  * toggles");
            addFreezeDetails(result, 8);
            if (thread == null) result.add("<none>");
            else {
                JvmDebuggerState stop = pausedStateForThread(thread);
                result.add("Name:     " + thread.name());
                result.add("State:    " + thread.stateSummary());
                result.add("Priority: " + thread.priority());
                result.add("Daemon:   " + thread.daemon());
                result.add("Debugger: " + (thread.debuggerPaused() ? "PAUSED" : "running/waiting"));
                if (stop != null) {
                    result.add("STOP HIT: " + stop.reason());
                    result.add("At:       " + stop.className() + "." + stop.methodName()
                            + stop.descriptor() + " BCI " + stop.location()
                            + (stop.sourceLine() < 0 ? "" : " line " + stop.sourceLine()));
                }
                result.add("");
                result.add("Breakpoint/watchpoint hits automatically open their current BCI.");
                result.add("");
                result.add("SHORTCUTS");
                addKeyHelp(result, "Enter / F6", stop == null
                        ? "Pause and follow this thread" : "Open this stop in Debug");
                addKeyHelp(result, "G", "Open current stop in Debug");
                addKeyHelp(result, "F7", "Execute one bytecode while paused");
                addKeyHelp(result, "Shift+F7", "Run until the current Java frame returns");
                addKeyHelp(result, "F8", "Continue this thread");
                addKeyHelp(result, "[ / ]", "Scroll clipped text horizontally");
                addKeyHelp(result, "0", "Reset horizontal position");
            }
        }
        return result;
    }

    private List<String> unloadedFieldInfo() {
        List<String> result = new ArrayList<String>();
        List<JvmClassPathCatalog.Member> visible = visibleUnloadedFields();
        result.add("UNLOADED CLASS: " + unloadedContextClass.name());
        result.add("Static fields: " + (showStaticMembers ? "visible" : "hidden") + "  (@ toggles)");
        result.add("Instance fields: " + (showVirtualMembers ? "visible" : "hidden") + "  (# toggles)");
        result.add("");
        if (visible.isEmpty()) {
            result.add(unloadedFields.isEmpty() ? "<no fields in class file>" : "No field matches active filters.");
            return result;
        }
        JvmClassPathCatalog.Member field = selectedUnloadedField();
        result.add((field.isStatic() ? "STATIC " : "INSTANCE ")
                + Modifier.toString(field.access()) + " " + field.typeSummary() + " " + field.name());
        result.add("Declared by: " + unloadedContextClass.name());
        result.add("Descriptor:  " + field.descriptor());
        result.add("State:       UNLOADED; metadata read from " + unloadedContextClass.origin());
        result.add("");
        result.add("Runtime values exist after class preparation; current offline actions are watches/decompile.");
        result.add("");
        result.add("SHORTCUTS");
        addKeyHelp(result, "U / W", "Toggle pending field-read / field-write watch");
        addKeyHelp(result, "@ / #", "Show/hide static / instance fields");
        addKeyHelp(result, "f / F", "Find in list / search unloaded catalog");
        addKeyHelp(result, "A", "Decompile owning class without loading it");
        addKeyHelp(result, "l", "Load/initialize owning class with Class.forName");
        addKeyHelp(result, "L", "Load/link owning class without running <clinit>");
        addKeyHelp(result, "Backspace", "Return to unloaded package browser");
        return result;
    }

    private List<String> unloadedMethodInfo() {
        List<String> result = new ArrayList<String>();
        List<JvmClassPathCatalog.Member> visible = visibleUnloadedMethods();
        result.add("UNLOADED CLASS: " + unloadedContextClass.name());
        result.add("Static methods: " + (showStaticMembers ? "visible" : "hidden") + "  (@ toggles)");
        result.add("Virtual methods: " + (showVirtualMembers ? "visible" : "hidden") + "  (# toggles)");
        result.add("");
        if (visible.isEmpty()) {
            result.add(unloadedMethods.isEmpty() ? "<no methods in class file>" : "No method matches active filters.");
            return result;
        }
        JvmClassPathCatalog.Member method = selectedUnloadedMethod();
        result.add((method.isStatic() ? "STATIC " : "INSTANCE ")
                + Modifier.toString(method.access()) + " " + method.typeSummary() + " " + method.name());
        result.add("Declared by:   " + unloadedContextClass.name());
        result.add("Descriptor:    " + method.descriptor());
        result.add("Implementation: " + (method.isNative() ? "NATIVE"
                : method.isAbstract() ? "ABSTRACT" : "BYTECODE"));
        result.add("State:         UNLOADED; bytecode is read offline");
        result.add("");
        result.add("SHORTCUTS");
        addKeyHelp(result, "Enter / B", "Open bytecode without loading the class");
        addKeyHelp(result, "S", "Decompile selected method without loading it");
        addKeyHelp(result, "A", "Decompile owning class without loading it");
        addKeyHelp(result, "F9", method.isNative() || method.isAbstract()
                ? "Toggle pending method-entry event breakpoint" : "Toggle pending BCI 0 breakpoint");
        addKeyHelp(result, "Ctrl+E / Ctrl+X", "Toggle method-entry / method-exit event breakpoint");
        addKeyHelp(result, "@ / #", "Show/hide static / virtual methods");
        addKeyHelp(result, "K", "Show/hide <init> and <clinit>");
        addKeyHelp(result, "Backspace", "Return to unloaded package browser");
        return result;
    }

    private static void addKeyHelp(List<String> lines, String key, String description) {
        lines.add(String.format(Locale.ROOT, "%-11s %s", key, description));
    }

    private void renderWorkbench(List<String> output, int width, int bodyRows) {
        if (inspectorFocused) {
            renderFocusedInspector(output, width, bodyRows);
            return;
        }
        boolean compactGutter = width < 78;
        int gutterWidth = compactGutter ? Math.min(14, width) : Math.min(23, width);
        int sideWidth = inspectorVisible(width) ? Math.min(64, Math.max(34, width * 31 / 100)) : 0;
        int separators = gutterWidth < width ? (sideWidth == 0 ? 3 : 6) : 0;
        int centerWidth = Math.max(0, width - gutterWidth - sideWidth - separators);
        List<BytecodeInstruction> instructions = bytecode == null
                ? Collections.<BytecodeInstruction>emptyList() : bytecode.instructions();
        long executionLocation = currentExecutionLocationForBytecode();
        int cursor = bytecodeCursor();
        int scroll = scrolls[tab.ordinal()];
        if (cursor < scroll) scroll = cursor;
        if (cursor >= scroll + bodyRows) scroll = cursor - bodyRows + 1;
        scrolls[tab.ordinal()] = scroll;
        List<String> side = debuggerSidePanel();
        int sideRows = Math.max(0, bodyRows - 1);
        inspectorScroll = clamp(inspectorScroll, 0, Math.max(0, side.size() - sideRows));
        for (int row = 0; row < bodyRows; row++) {
            int index = scroll + row;
            String gutter = "";
            String instructionText = "";
            if (index < instructions.size()) {
                BytecodeInstruction instruction = instructions.get(index);
                boolean stopped = executionLocation != Long.MIN_VALUE
                        && instruction.offset() == executionLocation;
                boolean breakpoint = hasBreakpointAt(
                        bytecodeClass, bytecodeMethod, bytecodeDescriptor, instruction.offset());
                String sourceLine = instruction.sourceLine() < 0 ? "-" : Integer.toString(instruction.sourceLine());
                gutter = compactGutter
                        ? String.format("%s%s %05d L%-4s", stopped ? ">" : " ", breakpoint ? "*" : " ",
                                instruction.offset(), sourceLine)
                        : String.format("%s%s #%04d B%05d L%-5s", stopped ? ">" : " ", breakpoint ? "*" : " ",
                                index, instruction.offset(), sourceLine);
                instructionText = instructionText(instruction);
            } else if (index == 0 && bytecode == null) {
                String foregroundActivity = tasks.activity();
                instructionText = !foregroundActivity.isEmpty() ? foregroundActivity
                        : tab == Tab.DEBUG
                                ? "Waiting for a stop; press T to select any JVM thread, then F6 to pause it."
                                : "Select a method and press B/Enter to load bytecode.";
            }
            else if (index == 0 && instructions.isEmpty()) instructionText = "<no Code attribute: native or abstract method>";
            String gutterCell = TerminalScreen.pad(gutter, gutterWidth);
            String visibleInstruction = TuiViewport.horizontal(
                    instructionText, horizontalOffsets[tab.ordinal()], centerWidth);
            String centerCell = TerminalScreen.pad(visibleInstruction, centerWidth);
            if (index < instructions.size() && (index == cursor
                    || instructions.get(index).offset() == executionLocation)) {
                boolean executing = instructions.get(index).offset() == executionLocation;
                String style = (executing ? TerminalScreen.BOLD + TerminalScreen.YELLOW : "")
                        + (index == cursor ? TerminalScreen.REVERSE : "");
                gutterCell = style + gutterCell + TerminalScreen.RESET;
                centerCell = style + centerCell + TerminalScreen.RESET;
            }
            String line = gutterCell;
            if (centerWidth > 0) line += " | " + centerCell;
            if (sideWidth > 0) {
                String sideText;
                if (row == 0) {
                    int last = Math.min(side.size(), inspectorScroll + sideRows);
                    sideText = "INFO [" + (side.isEmpty() ? 0 : inspectorScroll + 1)
                            + "-" + last + "/" + side.size() + "]  I open";
                } else {
                    int sideIndex = inspectorScroll + row - 1;
                    sideText = sideIndex < side.size() ? side.get(sideIndex) : "";
                }
                line += " | " + TerminalScreen.pad(TuiViewport.horizontal(
                        sideText, inspectorHorizontal, sideWidth), sideWidth);
            }
            output.add(line);
        }
    }

    private void renderFocusedInspector(List<String> output, int width, int bodyRows) {
        List<String> lines = debuggerSidePanel();
        int contentRows = Math.max(0, bodyRows - 1);
        inspectorScroll = clamp(inspectorScroll, 0, Math.max(0, lines.size() - contentRows));
        int last = Math.min(lines.size(), inspectorScroll + contentRows);
        output.add(TerminalScreen.REVERSE + TerminalScreen.pad(
                " DEBUG INFO  lines " + (lines.isEmpty() ? 0 : inspectorScroll + 1) + "-" + last
                        + "/" + lines.size() + "  I/Esc back  arrows/Pg scroll  [/]/0 horizontal",
                width) + TerminalScreen.RESET);
        for (int row = 0; row < contentRows; row++) {
            int index = inspectorScroll + row;
            String value = index < lines.size() ? lines.get(index) : "";
            String number = index < lines.size() ? String.format("%4d ", index + 1) : "     ";
            int textWidth = Math.max(0, width - number.length());
            output.add(TerminalScreen.DIM + number + TerminalScreen.RESET
                    + TerminalScreen.pad(TuiViewport.horizontal(value, inspectorHorizontal, textWidth), textWidth));
        }
    }

    private List<String> debuggerSidePanel() {
        List<String> result = new ArrayList<String>();
        result.add("BYTECODE");
        result.add(bytecodeClass.isEmpty() ? "<none>" : bytecodeClass + "." + bytecodeMethod);
        result.add(bytecodeDescriptor);
        result.add(bytecode == null ? "" : "maxStack=" + bytecode.maxStack()
                + " maxLocals=" + bytecode.maxLocals());
        if (bytecode != null) result.add("implementation=" + bytecode.implementationKind()
                + (bytecode.isNative() || bytecode.isAbstract() ? " (no Code attribute)" : ""));
        if (!bytecodeClass.isEmpty()
                && session.instrumentation().bytecode().hasStaged(bytecodeClass)) {
            result.add("STAGED: " + session.instrumentation().bytecode()
                    .stagedOperationCount(bytecodeClass) + " edit(s), not executing yet");
            result.add("F3 flush/verify; Shift+F3 discard");
        }
        if (bytecode != null && !bytecode.instructions().isEmpty()) {
            result.add("EDIT: + insert, - delete, ~ replace highlighted BCI");
            result.add("Insert accepts 'after: ASM'; separate instructions with ;;");
        }
        result.add("");
        result.add("DEBUGGER");
        result.add("ANALYSIS FREEZE: " + (session.debugger().active()
                ? "ACTIVE; " + session.debugger().ownedThreadCount() + " thread(s) owned"
                : "off") + "  (* toggles safely)");
        addFreezeDetails(result, 5);
        if (debuggerState == null) result.add("Checking state... (F5 refreshes now)");
        else if (debuggerState.paused()) {
            result.add("PAUSED: " + debuggerState.reason());
            result.add("ACTUAL TOP: " + debuggerState.className() + "."
                    + debuggerState.methodName() + debuggerState.descriptor());
            result.add(debuggerState.location() < 0
                    ? debuggerState.reason().startsWith("method_exit")
                            ? "TOP LOCATION: method return event (no next BCI in this frame)"
                            : "TOP LOCATION: native (BCI -1)"
                    : "TOP LOCATION: BCI " + debuggerState.location()
                            + "  line " + debuggerState.sourceLine());
            if (debuggerState.location() >= 0) {
                result.add("CURRENT BCI: yellow > marker; G selects and centres it");
            }
            if (!debuggerState.returnState().isEmpty()) {
                result.add(("allocation".equals(debuggerState.returnState())
                        ? "MATCHED STRING: " : "RETURN: ") + (debuggerState.returnValue() == null
                        ? debuggerState.returnState() : debuggerState.returnValue().displayValue()));
                result.add("METHOD_EXIT is observational; force return before this event.");
            }
            JvmStackFrame viewed = viewedDebuggerFrame();
            if (viewed != null) {
                result.add("VIEW FRAME #" + viewed.depth() + ": " + viewed.className()
                        + "." + viewed.methodName() + viewed.descriptor());
                result.add(viewed.hasJavaLocation() ? "VIEW LOCATION: BCI " + viewed.location()
                        : "VIEW LOCATION: native; choose a Java frame");
            }
            result.add("F7 steps; Shift+F7 steps out; F8 continues; Ctrl+R forces return");
            if (debuggerState.location() < 0
                    && !debuggerState.reason().startsWith("method_exit")) {
                result.add("F7 waits for the native call to return; it cannot reverse into a caller.");
            }
        } else if (debuggerState.enabled()) {
            result.add("RUNNING" + (liveFollowEnabled && !followedThreadName.isEmpty()
                    ? " - LIVE FOLLOW " + followedThreadName : " - waiting for next stop"));
            result.add("F4 live follow: " + (liveFollowEnabled ? "ON" : "off"));
            if (liveSampleAvailable) {
                result.add("LIVE SAMPLE age=" + Math.max(0L,
                        System.currentTimeMillis() - liveSampleCapturedAt) + "ms");
                result.add("ACTUAL: " + liveSampleActual);
                result.add("VIEW:   " + liveSampleView);
                result.add("CURRENT SAMPLE BCI: yellow > marker; G centres it");
                result.add("The thread was resumed immediately after this sample.");
            } else if (!liveSampleError.isEmpty()) {
                result.add("LIVE SAMPLE: " + liveSampleError);
            }
            result.add("Breakpoints are detected automatically");
            if (!lastStopSummary.isEmpty()) {
                result.add("Last stop: " + lastStopSummary);
                if (lastStopSummary.startsWith("main_entry")) {
                    result.add("main-entry is one-shot and is now cleared");
                    result.add("Use F9 on a reachable BCI to stop again");
                }
            }
        } else result.add("DISABLED - F9 enables it when setting a breakpoint");
        result.add("");
        result.add("STOPPED THREADS (" + pausedDebuggerCount() + ")");
        for (JvmDebuggerState state : debuggerStates) {
            if (!state.paused()) continue;
            result.add((state == debuggerState ? "> " : "  ")
                    + (state.thread() == null ? "<unknown>" : state.thread().displayValue()));
            result.add("    " + state.className() + "." + state.methodName()
                    + " @" + state.location() + " seq=" + state.sequence());
        }
        if (pausedDebuggerCount() == 0) result.add("<target running; no event thread is stopped>");
        result.add("");
        result.add("ALL JVM THREADS (" + debuggerThreads.size() + ")  T opens list");
        int shownThreads = 0;
        for (RemoteJvmtiThread thread : debuggerThreads) {
            if (shownThreads++ >= 12) {
                result.add("... " + (debuggerThreads.size() - 12) + " more; T opens full list");
                break;
            }
            JvmDebuggerState stop = pausedStateForThread(thread);
            result.add(stop == null ? "  " + thread.name() + "  " + thread.stateSummary()
                    : ">> STOP " + stop.reason() + "  " + thread.name()
                            + "  " + stop.methodName() + "@" + stop.location());
        }
        if (debuggerThreads.isEmpty()) result.add("<refreshing>");
        result.add("");
        if (debuggerState != null && debuggerState.paused()) {
            result.add("LOCALS (view frame " + debuggerFrameDepth + ")");
            if (!debuggerLocalsError.isEmpty()) result.add("<unavailable: " + debuggerLocalsError + ">");
            else if (debuggerLocals.isEmpty()) result.add("<no active variables or LocalVariableTable>");
            else for (JvmDebuggerLocal local : debuggerLocals) {
                result.add((local.inferred() ? "~" : "") + "[" + local.slot() + "] "
                        + local.name() + " " + local.descriptor());
                result.add("    " + (local.available()
                        ? local.value() == null ? "null" : local.value().displayValue()
                        : "<" + local.error() + ">"));
            }
        } else if (liveSampleAvailable) {
            result.add("LIVE SAMPLE LOCALS (frame " + debuggerFrameDepth + ")");
            if (!debuggerLocalsError.isEmpty()) result.add("<unavailable: " + debuggerLocalsError + ">");
            else if (debuggerLocals.isEmpty()) result.add("<no active variables or LocalVariableTable>");
            else for (JvmDebuggerLocal local : debuggerLocals) {
                result.add((local.inferred() ? "~" : "") + "[" + local.slot() + "] "
                        + local.name() + " " + local.descriptor());
                result.add("    " + (local.available()
                        ? local.value() == null ? "null" : local.value().displayValue()
                        : "<" + local.error() + ">"));
            }
        } else {
            result.add("LAST STOP LOCALS (snapshot)");
            if (lastDebuggerLocals.isEmpty()) result.add("<no stop captured yet>");
            else result.addAll(lastDebuggerLocals);
        }
        result.add("");
        if (debuggerState != null && debuggerState.paused()) {
            result.add("STACK TRACE (current; Frames tab selects depth)");
            if (debuggerStack.isEmpty()) result.add("<unavailable>");
            else for (int depth = 0; depth < debuggerStack.size(); depth++) {
                result.add((depth == debuggerFrameDepth ? "> " : "  ")
                        + "#" + depth + " " + debuggerStack.get(depth));
            }
        } else if (liveSampleAvailable) {
            result.add("LIVE SAMPLE STACK (thread already resumed)");
            if (debuggerStack.isEmpty()) result.add("<unavailable>");
            else for (int depth = 0; depth < debuggerStack.size(); depth++) {
                result.add((depth == debuggerFrameDepth ? "> " : "  ")
                        + "#" + depth + " " + debuggerStack.get(depth));
            }
        } else {
            result.add("LAST STOP STACK (snapshot)");
            if (lastDebuggerStack.isEmpty()) result.add("<no stop captured yet>");
            else for (String frame : lastDebuggerStack) result.add("  " + frame);
        }
        result.add("");
        result.add("SEARCH");
        result.add(viewSearch.isEmpty() ? "<none>" : viewSearch);
        for (String match : debugSearchResults) result.add("  " + match);
        result.add("");
        result.add("BREAKPOINTS (" + breakpoints.size() + ")");
        if (breakpoints.isEmpty()) result.add("<none>");
        else for (BreakpointSpec breakpoint : breakpoints.values()) {
            result.add("* " + breakpoint.methodName + " @" + breakpoint.bci
                    + (breakpoint.line < 0 ? "" : " L" + breakpoint.line)
                    + (breakpoint.receiverId == 0L ? "" : " [object]"));
        }
        result.add("WATCHPOINTS (" + fieldWatches.size() + ")");
        if (fieldWatches.isEmpty()) result.add("<none; U/W in Fields adds read/write watches>");
        else for (JvmFieldWatchInfo watch : fieldWatches.values()) {
            result.add("* " + watch.kind() + " " + watch.className() + "." + watch.fieldName());
        }
        result.add("");
        result.add("CONTEXT");
        result.add(session.context().isSet() ? session.context().description() : "<unset>");
        if (!errors.isEmpty()) {
            result.add("");
            result.add("RECENT ERRORS");
            for (String error : errors) result.add("! " + error);
        }
        result.add("");
        result.add("TOOLS");
        addKeyHelp(result, "I", "Open full information view");
        addKeyHelp(result, "T", "Open all JVM threads");
        addKeyHelp(result, "F4", "Toggle live RUNNING-thread sampling");
        addKeyHelp(result, "Tab", "Open Frames to inspect native callers");
        addKeyHelp(result, "G", "Jump to current execution BCI");
        addKeyHelp(result, "Y", "Continue all stopped threads");
        addKeyHelp(result, "*", session.debugger().active()
                ? "Restore analysis-freeze thread states" : "Freeze eligible threads for analysis");
        addKeyHelp(result, "F9", "Toggle normal breakpoint at selected BCI");
        addKeyHelp(result, "Shift+F9", "Toggle breakpoint for current object only");
        addKeyHelp(result, "F7", "Step one bytecode");
        addKeyHelp(result, "Shift+F7", "Step out to the caller");
        addKeyHelp(result, "F8", "Continue selected thread");
        addKeyHelp(result, "Ctrl+R", "Force the paused Java frame to return");
        addKeyHelp(result, "Ctrl+X", "Toggle pause on all thrown exceptions");
        addKeyHelp(result, "/", "Search current bytecode/debug view");
        addKeyHelp(result, "S", "Decompile current method");
        addKeyHelp(result, "[ / ]", "Scroll clipped text horizontally");
        addKeyHelp(result, "0", "Reset horizontal position");
        return result;
    }

    private void addFreezeDetails(List<String> result, int limit) {
        if (lastFreezeReport == null) return;
        int shown = 0;
        for (DebuggerFreezeReport.Entry entry : lastFreezeReport.entries()) {
            if (entry.action() != DebuggerFreezeReport.Action.FAILED
                    && entry.action() != DebuggerFreezeReport.Action.EXCLUDED) continue;
            if (shown++ >= limit) {
                result.add("... more freeze details available from CLI: debugger freeze-status");
                break;
            }
            result.add(entry.action().name() + " " + entry.threadName() + ": " + entry.detail());
        }
    }

    private List<String> helpTokens() {
        List<String> result = new ArrayList<String>();
        if (inspectorFocused) {
            Collections.addAll(result, "[ / ] Horizontal", "0 Reset", "I/Esc Bytecode",
                    "Up/Down Info", "PgUp/PgDn Page", "Home/End Edge",
                    "F7 Step", "F8 Run", "F2 CLI", "Q Back");
            return result;
        }
        Collections.addAll(result, "[ / ] Horizontal", "0 Reset", "l Initialize", "L Load No-Init",
                "M Locals", "Z Breakpoints");
        if (tab == Tab.BROWSE) Collections.addAll(result,
                "/ Filter", "f Find List", "F Global Find", ": Exact", "P Package", "J JDK",
                browseUnloaded ? "U Loaded Classes" : "U Unloaded Classes", "a Arrays",
                "A Class Decompile", "B Method Bytecode", "F9 Pending Break", "Backspace Parent");
        else if (tab == Tab.FIELDS) Collections.addAll(result,
                "/ Filter", "f Find List", "F Global Find", "@ Static", "# Instance", "Enter Read", "= Set",
                "& Track Ref", "; String Hook", "U Break Read", "W Break Write",
                "A Class Decompile", "Backspace Context", "D Dump");
        else if (tab == Tab.REFERENCES) Collections.addAll(result,
                "Enter Context", "S Save Strong", "Shift+S Save Weak", "= Replace", "X Set Null",
                "Delete Release", "F5 Refresh");
        else if (tab == Tab.METHODS) Collections.addAll(result,
                "/ Filter", "f Find List", "F Global Find", "@ Static", "# Virtual",
                "H Hide Object", "K <init>/<clinit>", "x/X Invoke/Exact", "Enter/B Bytecode",
                "F9 Break", "Shift+F9 Object Break", "Ctrl+E Entry Event", "Ctrl+X Exit Event",
                "S Method Decompile", "A Class Decompile");
        else if (tab == Tab.SOURCE) Collections.addAll(result,
                "G Current Frame", "Enter Bytecode", "/ Search", "n/N Match", "g Line", "F9 Break", "Shift+F9 Object Break",
                "A Class Decompile", "O Export");
        else if (tab == Tab.BYTECODE) Collections.addAll(result,
                "G Current Frame", "/ Search", "n/N Match", "g BCI/Line", "+ Insert ASM", "- Delete ASM", "~ Replace ASM",
                "| Try/Catch", "F3 Flush Edits", "Shift+F3 Discard Edits", "F9 Break", "Shift+F9 Object Break", "S Method Decompile",
                "V Range Decompile", "A Class Decompile", "I Info");
        else if (tab == Tab.DEBUG) Collections.addAll(result,
                "T Threads", "G Current", "+ Insert ASM", "- Delete ASM", "~ Replace ASM", "| Try/Catch",
                "F3 Flush Edits", "Shift+F3 Discard Edits", "F4 Live Follow", "F9 Break", "Shift+F9 Object Break", "F7 Step", "Shift+F7 Step Out", "F8 Run", "Ctrl+R Force Return", "Ctrl+X Exceptions", "Y Run All", "* Freeze/Thaw",
                "/ Search", "S Method Decompile", "V Range Decompile", "A Class Decompile", "I Info");
        else if (tab == Tab.STRINGS) Collections.addAll(result,
                "A Add Hook", "Enter Open", "F9 Enable/Disable/Rearm", "= Replace Field",
                "& Track Value", "Delete Remove", "F5 Refresh Hits");
        else if (tab == Tab.FRAMES) Collections.addAll(result,
                "Enter Debug Frame", "B Frame Bytecode", "S Frame Decompile", "G Frame BCI",
                "M Frame Locals", "T Threads", "F4 Live Follow", "* Freeze/Thaw", "F5 Refresh");
        else if (tab == Tab.LOCALS) Collections.addAll(result,
                "Enter To Context", "= Set Paused Local", "G Current BCI", "T Threads", "F4 Live Follow", "F5 Refresh");
        else if (tab == Tab.BREAKPOINTS) Collections.addAll(result,
                "Enter Open", "F9/Delete Clear", "A Clear All", "G Current BCI");
        else if (tab == Tab.THREADS) Collections.addAll(result,
                "Enter/F6 Pause", "G Open Stop", "F7 Step", "Shift+F7 Step Out", "F8 Run",
                "T Next", "F5 Refresh", "Y Run All", "* Freeze/Thaw");
        else Collections.addAll(result, "= Set Source", "A Class Decompile", "Backspace Context", "D Dump", "O Export");
        Collections.addAll(result, "Up/Down Move", "PgUp/PgDn Page", "Home/End Edge",
                horizontallyScrollable() ? "Left/Right Scroll" : "Left/Right Tab",
                "Tab/Shift+Tab View", "Ctrl+Left/Right Tab", "Enter Open", "F2 CLI", "Q Back");
        return result;
    }

    private String fieldLabel(RemoteField field) {
        String inherited = contextClass != null && !field.declaringClass().equals(contextClass.className()) ? "^" : " ";
        return (field.isStatic() ? "S" : "V") + inherited + " " + field.typeName() + " " + field.name();
    }

    private static String unloadedFieldLabel(JvmClassPathCatalog.Member field) {
        return "U" + (field.isStatic() ? "S" : "V") + " " + field.typeSummary() + " " + field.name();
    }

    private static String unloadedMethodLabel(JvmClassPathCatalog.Member method) {
        String implementation = method.isNative() ? "N" : method.isAbstract() ? "A" : " ";
        return "U" + (method.isStatic() ? "S" : "V") + implementation + " "
                + method.typeSummary() + " " + method.name();
    }

    private String methodLabel(RemoteMethod method) {
        String inherited = contextClass != null && !method.declaringClass().equals(contextClass.className()) ? "^" : " ";
        String special = method.isJvmSpecial() ? "J" : method.isNative() ? "N"
                : method.isAbstract() ? "A" : " ";
        return (method.isStatic() ? "S" : "V") + inherited + special + " " + method.returnTypeName() + " "
                + method.name() + "(" + String.join(", ", method.parameterTypeNames()) + ")";
    }

    private static String localLabel(JvmDebuggerLocal local) {
        String availability = local.available() ? " " : "!";
        String inferred = local.inferred() ? "~" : " ";
        String value = local.available() && local.value() != null
                ? local.value().displayValue() : local.error();
        return availability + inferred + "[" + local.slot() + "] " + local.name()
                + " " + local.descriptor() + " = " + value;
    }

    private static String referenceLabel(JvmReferenceInfo reference) {
        String value = reference.state().name();
        if (reference.state() == nhcm.jvmrtdp.api.reference.JvmReferenceState.LIVE) {
            value = reference.className() + "#" + reference.remoteId();
        } else if (reference.state() == nhcm.jvmrtdp.api.reference.JvmReferenceState.NULL) {
            value = "null";
        }
        return (reference.strength() == JvmReferenceStrength.WEAK ? "W" : "S")
                + " " + reference.name() + " = " + value;
    }

    private static String stringHookLabel(JvmStringHookInfo hook) {
        return (hook.enabled() ? "*" : " ") + " " + hook.name() + " "
                + hook.kind() + " " + hook.className() + "." + hook.memberName()
                + (hook.lastHit().isEmpty() ? "" : " [HIT]");
    }

    private String breakpointLabel(BreakpointSpec breakpoint) {
        return (isCurrentBreakpoint(breakpoint) ? "> HIT " : "  ")
                + breakpoint.className + "." + breakpoint.methodName
                + " @" + breakpoint.bci
                + (breakpoint.line < 0 ? "" : " L" + breakpoint.line)
                + (breakpoint.receiverId == 0L ? "" : " [object]");
    }

    private boolean isCurrentBreakpoint(BreakpointSpec breakpoint) {
        return debuggerState != null && debuggerState.paused()
                && breakpoint.className.equals(debuggerState.className())
                && breakpoint.methodName.equals(debuggerState.methodName())
                && breakpoint.descriptor.equals(debuggerState.descriptor())
                && breakpoint.bci == debuggerState.location();
    }

    private void releasePausedDebugger() {
        try {
            if (session.debugger().active()) session.debugger().restore();
            List<JvmDebuggerState> states = session.jvmti().debuggerStates();
            try {
                boolean paused = false;
                for (JvmDebuggerState state : states) paused |= state.paused();
                if (paused) session.jvmti().continueAllExecutions();
            } finally {
                for (JvmDebuggerState state : states) state.close();
            }
        } catch (RuntimeException ignored) { }
    }

    private static String instructionText(BytecodeInstruction instruction) {
        return String.format("%-16s %s", instruction.mnemonic(), instruction.operands());
    }

    private static String shortTabName(Tab value) {
        switch (value) {
            case BROWSE: return "brw";
            case CONTEXT: return "ctx";
            case REFERENCES: return "ref";
            case FIELDS: return "fld";
            case METHODS: return "mth";
            case SOURCE: return "dec";
            case BYTECODE: return "bc";
            case DEBUG: return "dbg";
            case STRINGS: return "str";
            case FRAMES: return "frm";
            case LOCALS: return "loc";
            case BREAKPOINTS: return "bp";
            case THREADS: return "thr";
            default: return value.name().toLowerCase(Locale.ROOT);
        }
    }

    private static String safeFileName(String value) {
        String normalized = value == null ? "export" : value.replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", "_")
                .replaceAll("\\s+", "_").replaceAll("_+", "_");
        while (normalized.endsWith(".") || normalized.endsWith("_")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) normalized = "export";
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120);
    }

    private static String joinLines(List<String> lines) {
        StringBuilder result = new StringBuilder();
        for (String line : lines) result.append(line == null ? "" : line).append(System.lineSeparator());
        return result.toString();
    }

    private static String glob(String expression) {
        if (expression == null || expression.trim().isEmpty()) return "*";
        String value = expression.trim();
        return value.indexOf('*') >= 0 || value.indexOf('?') >= 0 ? value : "*" + value + "*";
    }

    private static void addLines(List<String> target, String value) {
        Collections.addAll(target, value.split("\\r?\\n", -1));
    }

    private static boolean sameMethod(RemoteMethod left, RemoteMethod right) {
        return left.declaringClass().equals(right.declaringClass())
                && left.name().equals(right.name()) && left.descriptor().equals(right.descriptor());
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }

    private static boolean classNotLoaded(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && (message.contains("Class is not loaded")
                    || message.contains("Class not loaded")
                    || message.contains("not a loaded class"))) return true;
        }
        return false;
    }

    private static final class BytecodeLoadResult {
        private final ClassFileView view;
        private final JvmClassPathCatalog.ClassEntry catalogEntry;

        private BytecodeLoadResult(ClassFileView view,
                JvmClassPathCatalog.ClassEntry catalogEntry) {
            this.view = view;
            this.catalogEntry = catalogEntry;
        }
    }

    private static final class UnloadedPackageResult {
        private final JvmClassPathCatalog catalog;
        private final JvmClassPathCatalog.PackageView view;

        private UnloadedPackageResult(JvmClassPathCatalog catalog,
                JvmClassPathCatalog.PackageView view) {
            this.catalog = catalog;
            this.view = view;
        }
    }

    private static final class SearchResult {
        private final List<String> packages;
        private final List<RemoteClassInfo> classes;
        private final List<RemoteField> fields;
        private final List<RemoteMethod> methods;
        private SearchResult(List<String> packages, List<RemoteClassInfo> classes,
                List<RemoteField> fields, List<RemoteMethod> methods) {
            this.packages = packages; this.classes = classes;
            this.fields = fields; this.methods = methods;
        }
    }

    private static final class MemberPattern {
        private final String ownerGlob;
        private final String nameGlob;
        private MemberPattern(String ownerGlob, String nameGlob) {
            this.ownerGlob = ownerGlob; this.nameGlob = nameGlob;
        }
        private static MemberPattern parse(String expression) {
            String value = expression == null ? "" : expression.trim();
            int separator = value.indexOf('#');
            if (separator < 0) return new MemberPattern("*", glob(value));
            return new MemberPattern(glob(value.substring(0, separator)),
                    glob(value.substring(separator + 1)));
        }
    }

    private static final class ExportPayload {
        private final String baseName;
        private final String extension;
        private final String content;
        private ExportPayload(String baseName, String extension, String content) {
            this.baseName = baseName;
            this.extension = extension;
            this.content = content;
        }
    }

    private static final class ContextSnapshot {
        private final RemoteClass type;
        private final boolean classContext;
        private final List<RemoteField> fields;
        private final List<RemoteMethod> methods;
        private final List<String> valueLines;
        private final String specialError;
        private ContextSnapshot(RemoteClass type, boolean classContext,
                List<RemoteField> fields, List<RemoteMethod> methods, List<String> valueLines,
                String specialError) {
            this.type = type; this.classContext = classContext; this.fields = fields;
            this.methods = methods; this.valueLines = valueLines;
            this.specialError = specialError;
        }
    }

    private static final class LiveExecutionSample {
        private final String threadName;
        private final DebuggerSnapshot snapshot;
        private final String error;
        private final boolean realStopDetected;
        private final long previouslyObserved;

        private LiveExecutionSample(String threadName, DebuggerSnapshot snapshot,
                String error, boolean realStopDetected, long previouslyObserved) {
            this.threadName = threadName;
            this.snapshot = snapshot;
            this.error = error;
            this.realStopDetected = realStopDetected;
            this.previouslyObserved = previouslyObserved;
        }

        private static LiveExecutionSample captured(String threadName,
                DebuggerSnapshot snapshot) {
            return new LiveExecutionSample(threadName, snapshot, "", false, -1L);
        }

        private static LiveExecutionSample error(String threadName, String error) {
            return new LiveExecutionSample(threadName, null, error, false, -1L);
        }

        private static LiveExecutionSample realStop(long previouslyObserved) {
            return new LiveExecutionSample("", null, "", true, previouslyObserved);
        }
    }

    private static final class DebuggerSnapshot implements AutoCloseable {
        private final List<JvmDebuggerState> states;
        private final int selected;
        private final List<RemoteJvmtiThread> threads;
        private final List<String> stack;
        private final List<JvmStackFrame> frames;
        private final int frameDepth;
        private final List<JvmDebuggerLocal> locals;
        private final String localsError;
        private final ClassFileView stopBytecodeView;
        private final String stopBytecodeClass;
        private final String stopBytecodeMethod;
        private final String stopBytecodeDescriptor;
        private final long stopBytecodeLocation;
        private final String stopBytecodeError;
        private DebuggerSnapshot(List<JvmDebuggerState> states, int selected,
                List<RemoteJvmtiThread> threads, List<String> stack,
                List<JvmStackFrame> frames, int frameDepth,
                List<JvmDebuggerLocal> locals, String localsError,
                ClassFileView stopBytecodeView, String stopBytecodeClass,
                String stopBytecodeMethod, String stopBytecodeDescriptor,
                long stopBytecodeLocation, String stopBytecodeError) {
            this.states = states; this.selected = selected; this.threads = threads; this.stack = stack;
            this.frames = frames; this.frameDepth = frameDepth;
            this.locals = locals; this.localsError = localsError;
            this.stopBytecodeView = stopBytecodeView;
            this.stopBytecodeClass = stopBytecodeClass;
            this.stopBytecodeMethod = stopBytecodeMethod;
            this.stopBytecodeDescriptor = stopBytecodeDescriptor;
            this.stopBytecodeLocation = stopBytecodeLocation;
            this.stopBytecodeError = stopBytecodeError;
        }
        private JvmDebuggerState selectedState() {
            return states.isEmpty() ? null : states.get(clamp(selected, 0, states.size() - 1));
        }
        private long newestSequence() { return newestPausedSequence(states); }
        @Override public void close() {
            for (JvmDebuggerState state : states) state.close();
            for (RemoteJvmtiThread thread : threads) thread.close();
            for (JvmDebuggerLocal local : locals) local.close();
        }
    }

    private static final class BreakpointSpec {
        private final String className;
        private final String methodName;
        private final String descriptor;
        private final long bci;
        private final int line;
        private final String registrationId;
        private final long receiverId;
        private final String conditionSummary;
        private BreakpointSpec(String className, String methodName, String descriptor, long bci, int line) {
            this(className, methodName, descriptor, bci, line, "", 0L, "all receivers/callers");
        }
        private BreakpointSpec(String className, String methodName, String descriptor,
                long bci, int line, String registrationId, long receiverId,
                String conditionSummary) {
            this.className = className; this.methodName = methodName; this.descriptor = descriptor;
            this.bci = bci; this.line = line;
            this.registrationId = registrationId;
            this.receiverId = receiverId;
            this.conditionSummary = conditionSummary;
        }
        private String id() { return registrationId.isEmpty()
                ? id(className, methodName, descriptor, bci) + '|' + receiverId : registrationId; }
        private JvmBreakpointInfo info() {
            if (registrationId.isEmpty()) throw new IllegalStateException("Breakpoint is not registered");
            return new JvmBreakpointInfo(className, methodName, descriptor, bci,
                    registrationId, receiverId, conditionSummary);
        }
        private static String id(String className, String methodName, String descriptor, long bci) {
            return className + '|' + methodName + '|' + descriptor + '|' + bci;
        }
    }

    private static final class BreakpointClearResult {
        private final List<String> cleared;
        private final List<String> failures;

        private BreakpointClearResult(List<String> cleared, List<String> failures) {
            this.cleared = cleared;
            this.failures = failures;
        }
    }
}
