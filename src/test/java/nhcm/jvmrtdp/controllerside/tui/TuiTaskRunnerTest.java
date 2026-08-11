package nhcm.jvmrtdp.controllerside.tui;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TuiTaskRunnerTest {
    @Test
    void queuesNavigationBehindAVisibleRefresh() throws Exception {
        CountDownLatch releaseRefresh = new CountDownLatch(1);
        AtomicBoolean navigationApplied = new AtomicBoolean();

        try (TuiTaskRunner runner = new TuiTaskRunner("tui-task-test")) {
            assertTrue(runner.submit("Loading old context...", () -> {
                releaseRefresh.await(2, TimeUnit.SECONDS);
                return "old";
            }, ignored -> { }, failure -> { throw new AssertionError(failure); }));

            assertTrue(runner.submitOrQueue("Loading selected context...", () -> "selected",
                    ignored -> navigationApplied.set(true),
                    failure -> { throw new AssertionError(failure); }));

            releaseRefresh.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!navigationApplied.get() && System.nanoTime() < deadline) {
                runner.poll();
                Thread.sleep(5L);
            }
        }

        assertTrue(navigationApplied.get(), "queued navigation should run after refresh");
    }

    @Test
    void silentPollDoesNotBlockInputButItsQueuedActionDoes() throws Exception {
        CountDownLatch releasePoll = new CountDownLatch(1);

        try (TuiTaskRunner runner = new TuiTaskRunner("tui-poll-test")) {
            assertTrue(runner.submit("", () -> {
                releasePoll.await(2, TimeUnit.SECONDS);
                return null;
            }, ignored -> { }, failure -> { throw new AssertionError(failure); }));
            assertFalse(runner.userOperationBusy());

            assertTrue(runner.submitOrQueue("Open selected class...", () -> null,
                    ignored -> { }, failure -> { throw new AssertionError(failure); }));
            assertTrue(runner.userOperationBusy());
            releasePoll.countDown();
        }
    }

    @Test
    void contextNavigationReplacesAQueuedSilentPoll() throws Exception {
        CountDownLatch releaseActive = new CountDownLatch(1);
        AtomicBoolean contextLoaded = new AtomicBoolean();
        AtomicBoolean obsoletePollRan = new AtomicBoolean();

        try (TuiTaskRunner runner = new TuiTaskRunner("tui-context-sync-test")) {
            assertTrue(runner.submit("", () -> {
                releaseActive.await(2, TimeUnit.SECONDS);
                return null;
            }, ignored -> { }, failure -> { throw new AssertionError(failure); }));
            assertTrue(runner.submitOrQueue("", () -> null,
                    ignored -> obsoletePollRan.set(true),
                    failure -> { throw new AssertionError(failure); }));
            assertTrue(runner.submitOrQueue("Loading CLI-selected context...", () -> "members",
                    ignored -> contextLoaded.set(true),
                    failure -> { throw new AssertionError(failure); }));

            releaseActive.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!contextLoaded.get() && System.nanoTime() < deadline) {
                runner.poll();
                Thread.sleep(5L);
            }
        }

        assertTrue(contextLoaded.get(), "context refresh must survive CLI -> TUI switching");
        assertFalse(obsoletePollRan.get(), "obsolete silent refresh should be replaced");
    }
}
