package nhcm.jvmrtdp.bootstrap;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StringHookBridgeTest {
    @Test void prefiltersGlobPatternsWithoutAllocatingDerivedStrings() throws Exception {
        StringHookBridge.configure(new String[] { "secret-?", "TOKEN-*" },
                new boolean[] { true, false }, true);

        assertTrue(matches("secret-x"));
        assertFalse(matches("secret-long"));
        assertTrue(matches("token-value"));
        assertFalse(matches("ordinary"));
    }

    @Test void wildcardAllowsEveryContent() throws Exception {
        StringHookBridge.configure(new String[] { "*" }, new boolean[] { true }, true);
        assertTrue(matches(""));
        assertTrue(matches("anything"));
    }

    private static boolean matches(String value) throws Exception {
        Method method = StringHookBridge.class.getDeclaredMethod("matchesAnyContent", String.class);
        method.setAccessible(true);
        return ((Boolean) method.invoke(null, value)).booleanValue();
    }
}
