package com.vlessclient.service;

/**
 * Punycode (RFC 3492) for one label of a name, which java.net.IDN does only
 * after mapping the label by IDNA 2003. A label that must keep what that
 * mapping changes, such as a German sharp s, is encoded here as it is.
 */
final class Punycode {

    private static final int BASE = 36;
    private static final int T_MIN = 1;
    private static final int T_MAX = 26;
    private static final int SKEW = 38;
    private static final int DAMP = 700;
    private static final int INITIAL_BIAS = 72;
    private static final int INITIAL_N = 0x80;

    /** The longest label DNS carries. */
    private static final int MAX_LABEL = 63;

    private Punycode() {
    }

    /**
     * A label as DNS carries it: "xn--" and its punycode when it holds
     * anything outside ASCII, else as it is.
     *
     * @param label one label, without dots, already mapped and normalized
     * @return the ASCII label; the label as given when it would be longer
     *     than DNS takes, as IDN keeps one it cannot convert
     */
    static String label(String label) {
        if (label.chars().allMatch(c -> c < INITIAL_N)) {
            return label;
        }
        String ascii = "xn--" + encode(label.codePoints().toArray());
        return ascii.length() > MAX_LABEL ? label : ascii;
    }

    private static String encode(int[] input) {
        StringBuilder out = new StringBuilder();
        for (int c : input) {
            if (c < INITIAL_N) {
                out.append((char) c);
            }
        }
        int basic = out.length();
        int handled = basic;
        if (basic > 0) {
            out.append('-');
        }
        int n = INITIAL_N;
        long delta = 0;
        int bias = INITIAL_BIAS;
        while (handled < input.length) {
            int next = Integer.MAX_VALUE;
            for (int c : input) {
                if (c >= n && c < next) {
                    next = c;
                }
            }
            delta += (long) (next - n) * (handled + 1);
            n = next;
            for (int c : input) {
                if (c < n) {
                    delta++;
                }
                if (c == n) {
                    long q = delta;
                    for (int k = BASE; ; k += BASE) {
                        int t = k <= bias ? T_MIN : k >= bias + T_MAX ? T_MAX : k - bias;
                        if (q < t) {
                            break;
                        }
                        out.append(digit((int) (t + (q - t) % (BASE - t))));
                        q = (q - t) / (BASE - t);
                    }
                    out.append(digit((int) q));
                    bias = adapt(delta, handled + 1, handled == basic);
                    delta = 0;
                    handled++;
                }
            }
            delta++;
            n++;
        }
        return out.toString();
    }

    private static int adapt(long delta, int points, boolean first) {
        long d = first ? delta / DAMP : delta / 2;
        d += d / points;
        int k = 0;
        while (d > ((BASE - T_MIN) * T_MAX) / 2) {
            d /= BASE - T_MIN;
            k += BASE;
        }
        return (int) (k + (BASE - T_MIN + 1) * d / (d + SKEW));
    }

    private static char digit(int d) {
        return (char) (d < 26 ? 'a' + d : '0' + d - 26);
    }
}
