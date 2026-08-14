package nhcm.jvmrtdp.api.hook;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JvmStringHookInfoTest {
    @Test void reportsBoundedAllocationAsDoneAtLimit() {
        JvmStringAllocationSpec spec = JvmStringAllocationSpec.builder()
                .maximumHits(2L).build();
        JvmStringHookInfo running = new JvmStringHookInfo("value",
                JvmStringHookKind.ALLOCATION, "java.lang.String", "<allocation>",
                "Ljava/lang/String;", false, true, 1L, "hit", spec, 1L, "one");
        JvmStringHookInfo done = new JvmStringHookInfo("value",
                JvmStringHookKind.ALLOCATION, "java.lang.String", "<allocation>",
                "Ljava/lang/String;", false, true, 2L, "hit", spec, 2L, "two");

        assertFalse(running.exhausted());
        assertTrue(done.exhausted());
        assertTrue(done.toString().contains("[DONE]"));
    }
}
