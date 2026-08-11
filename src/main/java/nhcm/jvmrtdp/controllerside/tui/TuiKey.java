package nhcm.jvmrtdp.controllerside.tui;

final class TuiKey {
    static final int EOF = -1;
    static final int NONE = -2;
    static final int UP = 0x1001;
    static final int DOWN = 0x1002;
    static final int LEFT = 0x1003;
    static final int RIGHT = 0x1004;
    static final int SHIFT_TAB = 0x1005;
    static final int F2 = 0x1012;
    static final int F4 = 0x1014;
    static final int F5 = 0x1015;
    static final int F6 = 0x1016;
    static final int F7 = 0x1017;
    static final int F8 = 0x1018;
    static final int F9 = 0x1019;
    static final int SHIFT_F9 = 0x1119;
    static final int F10 = 0x1020;
    static final int HOME = 0x1030;
    static final int END = 0x1031;
    static final int PAGE_UP = 0x1032;
    static final int PAGE_DOWN = 0x1033;
    static final int CTRL_U = 21;
    static final int CTRL_C = 3;
    static final int CTRL_G = 7;
    static final int ENTER = 13;
    static final int TAB = 9;
    static final int ESCAPE = 27;
    static final int BACKSPACE = 8;
    static final int DELETE = 127;
    private TuiKey() {}
}
