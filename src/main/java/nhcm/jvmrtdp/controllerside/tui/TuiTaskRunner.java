package nhcm.jvmrtdp.controllerside.tui;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;

/** One-at-a-time daemon worker used to keep network/native operations off the render thread. */
final class TuiTaskRunner implements AutoCloseable {
    private final ExecutorService executor;
    private Future<Object> future;
    private Consumer<Object> success;
    private Consumer<Throwable> failure;
    private String label = "";
    private long startedAt;
    private Callable<Object> pendingOperation;
    private Consumer<Object> pendingSuccess;
    private Consumer<Throwable> pendingFailure;
    private String pendingLabel = "";

    TuiTaskRunner(String threadName) {
        executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable action) {
                Thread thread = new Thread(action, threadName);
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    <T> boolean submit(String operationLabel, Callable<T> operation,
            Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
        Objects.requireNonNull(operation, "operation");
        if (busy()) return false;
        start(operationLabel, operation, onSuccess, onFailure);
        return true;
    }

    /** Queues one foreground action behind a silent debugger poll. */
    <T> boolean submitOrQueue(String operationLabel, Callable<T> operation,
            Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
        Objects.requireNonNull(operation, "operation");
        if (!busy()) return submit(operationLabel, operation, onSuccess, onFailure);
        if (!label.isEmpty() || pendingOperation != null) return false;
        pendingLabel = Objects.requireNonNull(operationLabel, "operationLabel");
        @SuppressWarnings("unchecked")
        Callable<Object> convertedOperation = (Callable<Object>) (Callable<?>) operation;
        @SuppressWarnings("unchecked")
        Consumer<Object> convertedSuccess = (Consumer<Object>) (Consumer<?>) Objects.requireNonNull(
                onSuccess, "onSuccess");
        pendingOperation = convertedOperation;
        pendingSuccess = convertedSuccess;
        pendingFailure = Objects.requireNonNull(onFailure, "onFailure");
        return true;
    }

    private <T> void start(String operationLabel, Callable<T> operation,
            Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
        label = Objects.requireNonNull(operationLabel, "operationLabel");
        startedAt = System.currentTimeMillis();
        @SuppressWarnings("unchecked")
        Consumer<Object> converted = (Consumer<Object>) (Consumer<?>) Objects.requireNonNull(onSuccess, "onSuccess");
        success = converted;
        failure = Objects.requireNonNull(onFailure, "onFailure");
        future = executor.submit(new Callable<Object>() {
            @Override public Object call() throws Exception { return operation.call(); }
        });
    }

    boolean busy() { return future != null; }

    String activity() {
        if (!busy()) return "";
        if (label.isEmpty()) return "";
        String[] frames = {"|", "/", "-", "\\"};
        long elapsedMillis = System.currentTimeMillis() - startedAt;
        int frame = (int) (elapsedMillis / 160L % frames.length);
        String elapsed = elapsedMillis < 1000L ? ""
                : " [" + (elapsedMillis / 1000L) + "s]";
        return frames[frame] + " " + label + elapsed;
    }

    void poll() {
        if (future == null || !future.isDone()) return;
        Future<Object> completed = future;
        Consumer<Object> completedSuccess = success;
        Consumer<Throwable> completedFailure = failure;
        future = null;
        success = null;
        failure = null;
        label = "";
        try {
            completedSuccess.accept(completed.get());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            completedFailure.accept(interrupted);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause() == null ? failed : failed.getCause();
            completedFailure.accept(cause);
        } catch (RuntimeException failed) {
            completedFailure.accept(failed);
        } finally {
            startPendingIfIdle();
        }
    }

    private void startPendingIfIdle() {
        if (future != null || pendingOperation == null) return;
        Callable<Object> operation = pendingOperation;
        Consumer<Object> onSuccess = pendingSuccess;
        Consumer<Throwable> onFailure = pendingFailure;
        String operationLabel = pendingLabel;
        pendingOperation = null;
        pendingSuccess = null;
        pendingFailure = null;
        pendingLabel = "";
        start(operationLabel, operation, onSuccess, onFailure);
    }

    @Override public void close() {
        if (future != null) future.cancel(true);
        pendingOperation = null;
        pendingSuccess = null;
        pendingFailure = null;
        executor.shutdownNow();
    }
}
