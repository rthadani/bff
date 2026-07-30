package bff.validator;

import io.github.rthadani.bff.BffValidator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Convenience base class for implementing custom BFF validators in Java.
 *
 * <p>Extend this class and implement {@link #validate(Map, Map)} — return an
 * empty list if the args are valid, or a list of human-readable error messages
 * to fail validation. The base class wraps each message into the {@code {"message": ...}}
 * shape the engine expects, so you never touch Clojure data structures.
 *
 * <pre>{@code
 * public class OrderValidator extends BaseValidator {
 *     @Override
 *     public List<String> validate(Map<String, Object> args, Map<String, Object> ctx) {
 *         List<String> errors = new ArrayList<>();
 *         Double amount = (Double) args.get("amount");
 *         String currency = (String) args.get("currency");
 *         if (amount != null && amount > 10000 && "GBP".equals(currency)) {
 *             errors.add("GBP orders above 10000 require manual approval");
 *         }
 *         return errors;
 *     }
 * }
 * }</pre>
 *
 * <p>Register the instance before {@code bff.core/create-handler} runs — via the
 * {@code Bff} facade or {@code bff.validator/register-validator!}.
 */
public abstract class BaseValidator implements BffValidator {

    @Override
    public final List<Map<String, Object>> validate(Map<String, Object> args, Map<String, Object> ctx) {
        List<String> messages = doValidate(args, ctx);
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>(messages.size());
        for (String msg : messages) {
            Map<String, Object> err = new HashMap<>();
            err.put("message", msg);
            out.add(err);
        }
        return out;
    }

    /**
     * Validate the request arguments.
     *
     * @param args GraphQL input arguments, keyed by argument name
     * @param ctx  Request context — forwarded headers and remote-addr
     * @return empty list if valid; a list of error messages if invalid
     */
    protected abstract List<String> doValidate(Map<String, Object> args, Map<String, Object> ctx);
}
