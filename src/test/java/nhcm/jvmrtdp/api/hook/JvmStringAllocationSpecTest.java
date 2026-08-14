package nhcm.jvmrtdp.api.hook;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JvmStringAllocationSpecTest {
    @Test void defaultsMatchEveryObservableStringAndCreator() {
        JvmStringAllocationSpec spec = JvmStringAllocationSpec.any();

        assertEquals("*", spec.contentPattern());
        assertEquals("*", spec.creatorClassPattern());
        assertEquals("*", spec.creatorMethodPattern());
        assertEquals("*", spec.creatorDescriptorPattern());
        assertTrue(spec.caseSensitive());
        assertEquals(JvmStringAllocationMode.FAST, spec.mode());
        assertEquals(0L, spec.maximumHits());
        assertEquals(1, spec.sampleEvery());
    }

    @Test void normalizesClassSeparatorsAndRetainsGlobFilters() {
        JvmStringAllocationSpec spec = JvmStringAllocationSpec.builder()
                .contentGlob("*secret-?*")
                .createdFrom("com/example/*", "create*", "(I)Ljava/lang/String;")
                .caseSensitive(false)
                .build();

        assertEquals("*secret-?*", spec.contentPattern());
        assertEquals("com.example.*", spec.creatorClassPattern());
        assertEquals("create*", spec.creatorMethodPattern());
        assertEquals("(I)Ljava/lang/String;", spec.creatorDescriptorPattern());
        assertFalse(spec.caseSensitive());
    }

    @Test void containingRejectsNullAndBuildsContainsGlob() {
        assertEquals("*token*", JvmStringAllocationSpec.containing("token").contentPattern());
        assertThrows(IllegalArgumentException.class,
                () -> JvmStringAllocationSpec.containing(null));
    }

    @Test void supportsCompleteModeAndBoundedSampling() {
        JvmStringAllocationSpec spec = JvmStringAllocationSpec.builder()
                .mode(JvmStringAllocationMode.COMPLETE)
                .maximumHits(4L)
                .sampleEvery(3)
                .build();

        assertEquals(JvmStringAllocationMode.COMPLETE, spec.mode());
        assertEquals(4L, spec.maximumHits());
        assertEquals(3, spec.sampleEvery());
        assertTrue(spec.summary().contains("mode=complete"));
        assertTrue(spec.summary().contains("max-hits=4"));
    }

    @Test void rejectsInvalidHitPolicies() {
        assertThrows(IllegalArgumentException.class,
                () -> JvmStringAllocationSpec.builder().maximumHits(-1L).build());
        assertThrows(IllegalArgumentException.class,
                () -> JvmStringAllocationSpec.builder().sampleEvery(0).build());
    }
}
