package nhcm.jvmrtdp.api.jvmti;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JvmBreakpointConditionTest {
    @Test
    void callerPatternsAreExposedAsStableSdkMetadata() {
        JvmBreakpointCondition condition = JvmBreakpointCondition.any()
                .calledFrom("com.example.*", "dispatch?", "(I)*");

        assertFalse(condition.isUnconditional());
        assertEquals("com.example.*", condition.callerClass());
        assertEquals("dispatch?", condition.callerMethod());
        assertTrue(condition.summary().contains("caller=com.example.*#dispatch?"));
    }

    @Test
    void breakpointInfoRetainsRegistrationAndReceiverCondition() {
        JvmBreakpointInfo info = new JvmBreakpointInfo("a.B", "run", "()V", 7L,
                "bp-id", 42L, "receiver#42");

        assertEquals("bp-id", info.id());
        assertTrue(info.objectSpecific());
        assertEquals(42L, info.receiverId());
    }
}
