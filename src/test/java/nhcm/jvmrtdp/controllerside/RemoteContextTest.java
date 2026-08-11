package nhcm.jvmrtdp.controllerside;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteContextTest {
    @Test
    void persistentChangesAdvanceTheRevision() {
        RemoteContext context = new RemoteContext();
        long before = context.revision();

        context.clear();

        assertTrue(context.revision() > before);
    }

    @Test
    void temporaryPipelineChangesRestoreTheRevision() {
        RemoteContext context = new RemoteContext();
        context.clear();
        long persistentRevision = context.revision();

        try (RemoteContext.TemporaryScope ignored = context.temporaryScope()) {
            context.clear();
            assertTrue(context.revision() > persistentRevision);
        }

        assertEquals(persistentRevision, context.revision());
    }
}
