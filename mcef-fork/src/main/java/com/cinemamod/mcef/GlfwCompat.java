package com.cinemamod.mcef;

/**
 * Minecraft 26.3 replaced GLFW with SDL. MCEF's key logic was written against GLFW values, so
 * the SDL scancodes/modifiers coming from Minecraft are translated here and the old logic is kept.
 */
final class GlfwCompat {
    private GlfwCompat() {}

    static final int GLFW_RELEASE = 0, GLFW_PRESS = 1;
    static final int GLFW_MOD_SHIFT = 1, GLFW_MOD_CONTROL = 2, GLFW_MOD_ALT = 4, GLFW_MOD_SUPER = 8;
    static final int GLFW_KEY_SPACE = 32, GLFW_KEY_0 = 48, GLFW_KEY_MINUS = 45, GLFW_KEY_EQUAL = 61, GLFW_KEY_R = 82;
    static final int GLFW_KEY_ESCAPE = 256, GLFW_KEY_ENTER = 257, GLFW_KEY_TAB = 258, GLFW_KEY_BACKSPACE = 259,
            GLFW_KEY_INSERT = 260, GLFW_KEY_DELETE = 261, GLFW_KEY_RIGHT = 262, GLFW_KEY_LEFT = 263,
            GLFW_KEY_DOWN = 264, GLFW_KEY_UP = 265, GLFW_KEY_PAGE_UP = 266, GLFW_KEY_PAGE_DOWN = 267,
            GLFW_KEY_HOME = 268, GLFW_KEY_END = 269, GLFW_KEY_KP_ENTER = 335;

    /** SDL modifier bits (L|R pairs) to GLFW bits. */
    static int fromSdlMods(int m) {
        int r = 0;
        if ((m & 3) != 0) r |= GLFW_MOD_SHIFT;
        if ((m & 192) != 0) r |= GLFW_MOD_CONTROL;
        if ((m & 768) != 0) r |= GLFW_MOD_ALT;
        if ((m & 3072) != 0) r |= GLFW_MOD_SUPER;
        return r;
    }

    /** SDL scancode to the GLFW key value (letters/digits equal their ASCII upper-case/digit code). */
    static int fromSdlScancode(int sc) {
        if (sc >= 4 && sc <= 29) return 'A' + (sc - 4);
        if (sc >= 30 && sc <= 38) return '1' + (sc - 30);
        if (sc == 39) return '0';
        if (sc >= 58 && sc <= 69) return 290 + (sc - 58); // F1..F12
        switch (sc) {
            case 40: return GLFW_KEY_ENTER;
            case 41: return GLFW_KEY_ESCAPE;
            case 42: return GLFW_KEY_BACKSPACE;
            case 43: return GLFW_KEY_TAB;
            case 44: return GLFW_KEY_SPACE;
            case 45: return GLFW_KEY_MINUS;
            case 46: return GLFW_KEY_EQUAL;
            case 47: return 91;  // [
            case 48: return 93;  // ]
            case 49: return 92;  // backslash
            case 51: return 59;  // ;
            case 52: return 39;  // '
            case 53: return 96;  // `
            case 54: return 44;  // ,
            case 55: return 46;  // .
            case 56: return 47;  // /
            case 73: return GLFW_KEY_INSERT;
            case 74: return GLFW_KEY_HOME;
            case 75: return GLFW_KEY_PAGE_UP;
            case 76: return GLFW_KEY_DELETE;
            case 77: return GLFW_KEY_END;
            case 78: return GLFW_KEY_PAGE_DOWN;
            case 79: return GLFW_KEY_RIGHT;
            case 80: return GLFW_KEY_LEFT;
            case 81: return GLFW_KEY_DOWN;
            case 82: return GLFW_KEY_UP;
            case 88: return GLFW_KEY_KP_ENTER;
            default: return sc;
        }
    }
}
