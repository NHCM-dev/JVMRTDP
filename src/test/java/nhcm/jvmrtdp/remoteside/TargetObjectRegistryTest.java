package nhcm.jvmrtdp.remoteside;

import nhcm.jvmrtdp.protocol.RemoteObjectDescriptor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TargetObjectRegistryTest {
    @Test
    void retainedStrongHandleHasIndependentLifetime() {
        TargetObjectRegistry registry = new TargetObjectRegistry();
        RemoteObjectDescriptor source = registry.store("tracked");
        RemoteObjectDescriptor retained = registry.retain(source.id(), false);

        registry.release(source.id());

        assertEquals("released", registry.status(source.id()));
        assertEquals("live", registry.status(retained.id()));
        assertEquals("tracked", registry.describe(retained.id()).displayValue());
    }

    @Test
    void weakNullIsReportedAsNullRatherThanCollected() {
        TargetObjectRegistry registry = new TargetObjectRegistry();
        RemoteObjectDescriptor source = registry.store(null);
        RemoteObjectDescriptor retained = registry.retain(source.id(), true);

        registry.release(source.id());

        assertEquals("null", registry.status(retained.id()));
        assertEquals(true, registry.describe(retained.id()).nullValue());
    }

    @Test
    void releaseWorksForWeakHandle() {
        TargetObjectRegistry registry = new TargetObjectRegistry();
        RemoteObjectDescriptor source = registry.store(new Object());
        RemoteObjectDescriptor weak = registry.retain(source.id(), true);

        assertEquals("live", registry.status(weak.id()));
        registry.release(weak.id());
        assertEquals("released", registry.status(weak.id()));
    }
}
