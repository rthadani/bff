package io.github.rthadani.bff;

/**
 * Custom GraphQL scalar type. Declared in the spec's top-level {@code scalars:}
 * list by name, then supplied to the handler via {@link BffConfig.Builder#scalar}.
 *
 * <p>Implementations provide two conversions:
 * <ul>
 *   <li>{@link #parse(Object)} — client input → internal value. Throw on invalid input.</li>
 *   <li>{@link #serialize(Object)} — internal value → JSON primitive
 *       (String / Number / Boolean / null).</li>
 * </ul>
 *
 * <p>Example — a MAC address scalar:
 * <pre>{@code
 * public class MacScalar implements BffScalar {
 *     private static final java.util.regex.Pattern MAC =
 *         java.util.regex.Pattern.compile("^([0-9a-f]{2}:){5}[0-9a-f]{2}$", java.util.regex.Pattern.CASE_INSENSITIVE);
 *
 *     @Override public Object parse(Object value) {
 *         String s = value.toString().toLowerCase();
 *         if (!MAC.matcher(s).matches()) {
 *             throw new IllegalArgumentException("Not a MAC address: " + value);
 *         }
 *         return s;
 *     }
 *
 *     @Override public Object serialize(Object value) {
 *         return value.toString();
 *     }
 * }
 * }</pre>
 */
public interface BffScalar {
    Object parse(Object value);
    Object serialize(Object value);
}
