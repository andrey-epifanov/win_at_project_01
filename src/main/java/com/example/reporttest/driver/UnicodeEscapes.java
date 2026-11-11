package com.example.reporttest.driver;

/**
 * Small helper to decode Java-style backslash-uXXXX escapes present inside
 * property values.  Spring Boot does NOT translate these escapes, so a value
 * that arrives as a sequence of those escape tokens stays literal unless we
 * decode it here.  The tokens are built from char literals so this source file
 * stays ASCII-clean and does not trip the javac unicode-escape preprocessor.
 */
public final class UnicodeEscapes {

    private UnicodeEscapes() {
    }

    public static String decode(String s) {
        if (s == null) {
            return s;
        }
        String decoded = decodeEscapes(s);
        return fixUtf8ReadAsLatin1(decoded);
    }

    private static String decodeEscapes(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        char slash = '\\';
        char u = 'u';
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == slash && i + 1 < s.length() && s.charAt(i + 1) == u && i + 6 <= s.length()) {
                try {
                    int cp = Integer.parseInt(s.substring(i + 2, i + 6), 16);
                    out.append((char) cp);
                    i += 5;
                    continue;
                } catch (NumberFormatException ignored) {
                    // not a valid escape; fall through and keep the char
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * Repair a string whose Cyrillic arrived as UTF-8 bytes that were read as
     * ISO-8859-1 (Latin-1), which is how Spring Boot reads .properties and how
     * some .properties loaders behave.  Such a value contains chars in the
     * 0x80..0xFF range; re-encoding those chars as ISO-8859-1 and decoding the
     * result as UTF-8 yields the real Cyrillic text.  If the value is already
     * clean (ASCII or proper Unicode) it is returned unchanged.
     */
    private static String fixUtf8ReadAsLatin1(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        boolean suspicious = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x80 && c <= 0xFF) {
                suspicious = true;
                break;
            }
        }
        if (!suspicious) {
            return s;
        }
        try {
            byte[] bytes = s.getBytes(java.nio.charset.Charset.forName("ISO-8859-1"));
            String fixed = new String(bytes, java.nio.charset.Charset.forName("UTF-8"));
            return fixed;
        } catch (Exception e) {
            return s;
        }
    }
}