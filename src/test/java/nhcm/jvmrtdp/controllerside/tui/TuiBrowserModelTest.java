package nhcm.jvmrtdp.controllerside.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class TuiBrowserModelTest {
    @Test void unloadedContextBackNavigationUsesOwningPackage() {
        assertEquals("com.example", TuiBrowserModel.parentPackage("com.example.Future"));
        assertEquals("", TuiBrowserModel.parentPackage("Future"));
    }
}
