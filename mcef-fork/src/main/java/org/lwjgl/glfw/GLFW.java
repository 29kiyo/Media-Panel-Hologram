package org.lwjgl.glfw;

/**
 * Minecraft 26.3 removed GLFW, but the prebuilt jcef native library still looks up this class
 * (static int constants and glfwGetKeyScancode) via JNI. This stub provides them with the real
 * GLFW values. Only for 26.3+; do not ship on branches where the real GLFW exists.
 */
public final class GLFW {
    private GLFW() {}

    public static final int GLFW_RELEASE = 0, GLFW_PRESS = 1, GLFW_REPEAT = 2;
    public static final int GLFW_MOUSE_BUTTON_1 = 0, GLFW_MOUSE_BUTTON_2 = 1, GLFW_MOUSE_BUTTON_3 = 2;
    public static final int GLFW_MOD_SHIFT = 1, GLFW_MOD_CONTROL = 2, GLFW_MOD_ALT = 4, GLFW_MOD_SUPER = 8;

    public static final int GLFW_KEY_ENTER = 257, GLFW_KEY_BACKSPACE = 259, GLFW_KEY_INSERT = 260,
            GLFW_KEY_DELETE = 261, GLFW_KEY_RIGHT = 262, GLFW_KEY_LEFT = 263, GLFW_KEY_DOWN = 264,
            GLFW_KEY_UP = 265, GLFW_KEY_PAGE_UP = 266, GLFW_KEY_PAGE_DOWN = 267, GLFW_KEY_HOME = 268,
            GLFW_KEY_END = 269, GLFW_KEY_CAPS_LOCK = 280, GLFW_KEY_SCROLL_LOCK = 281,
            GLFW_KEY_NUM_LOCK = 282, GLFW_KEY_PRINT_SCREEN = 283, GLFW_KEY_PAUSE = 284,
            GLFW_KEY_KP_2 = 322, GLFW_KEY_KP_4 = 324, GLFW_KEY_KP_6 = 326, GLFW_KEY_KP_8 = 328,
            GLFW_KEY_KP_ENTER = 335, GLFW_KEY_LEFT_CONTROL = 341, GLFW_KEY_RIGHT_CONTROL = 345;

    /** Real GLFW returns the platform scancode; unknown here. */
    public static int glfwGetKeyScancode(int key) {
        int sc = windowsScancode(key);
        return sc;
    }

    private static int windowsScancode(int key) {
        if (key >= 65 && key <= 90) {
            int[] t = new int[26];
            String q = "QWERTYUIOP", a = "ASDFGHJKL", z = "ZXCVBNM";
            for (int i = 0; i < q.length(); i++) t[q.charAt(i) - 'A'] = 0x10 + i;
            for (int i = 0; i < a.length(); i++) t[a.charAt(i) - 'A'] = 0x1E + i;
            for (int i = 0; i < z.length(); i++) t[z.charAt(i) - 'A'] = 0x2C + i;
            return t[key - 65];
        }
        if (key >= 49 && key <= 57) return 0x02 + (key - 49);
        if (key == 48) return 0x0B;
        switch (key) {
            case 256: return 0x01;   // Esc
            case 257: return 0x1C;   // Enter
            case 258: return 0x0F;   // Tab
            case 259: return 0x0E;   // Backspace
            case 32: return 0x39;    // Space
            case 260: return 0x152;  // Insert
            case 261: return 0x153;  // Delete
            case 262: return 0x14D;  // Right
            case 263: return 0x14B;  // Left
            case 264: return 0x150;  // Down
            case 265: return 0x148;  // Up
            case 266: return 0x149;  // PageUp
            case 267: return 0x151;  // PageDown
            case 268: return 0x147;  // Home
            case 269: return 0x14F;  // End
            case 335: return 0x11C;  // KP Enter
            case 341: return 0x1D;   // Left Ctrl
            case 345: return 0x11D;  // Right Ctrl
            default: return 0;
        }
    }
}
