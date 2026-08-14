package nhcm.jvmrtdp.controllerside;

import nhcm.jvmrtdp.api.hook.JvmStringAllocationMode;
import nhcm.jvmrtdp.api.hook.JvmStringAllocationSpec;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StringAllocationSpecParserTest {
    @Test void parsesPositionalCreatorAndOrderIndependentPolicies() {
        JvmStringAllocationSpec spec = StringAllocationSpecParser.parse(Arrays.asList(
                "allocation", "named", "*token*", "com.example.*", "make*", "(*)*",
                "ignore-case", "complete", "sample=4", "max=8"), 2);

        assertEquals("*token*", spec.contentPattern());
        assertEquals("com.example.*", spec.creatorClassPattern());
        assertEquals("make*", spec.creatorMethodPattern());
        assertEquals("(*)*", spec.creatorDescriptorPattern());
        assertFalse(spec.caseSensitive());
        assertEquals(JvmStringAllocationMode.COMPLETE, spec.mode());
        assertEquals(4, spec.sampleEvery());
        assertEquals(8L, spec.maximumHits());
    }

    @Test void parsesOneShotFastDefaults() {
        JvmStringAllocationSpec spec = StringAllocationSpecParser.parse(
                Arrays.asList("name", "hello*", "once"), 1);
        assertEquals(JvmStringAllocationMode.FAST, spec.mode());
        assertEquals(1L, spec.maximumHits());
    }

    @Test void rejectsUnknownOptionsAfterCreatorSlots() {
        assertThrows(IllegalArgumentException.class, () -> StringAllocationSpecParser.parse(
                Arrays.asList("name", "*", "a", "b", "c", "unknown"), 1));
    }
}
