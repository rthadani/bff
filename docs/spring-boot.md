# Spring Boot 3 integration

Spring Boot 3 uses `jakarta.servlet.*`, which is incompatible with
`ring.util.servlet`. The bridge below maps directly between
`HttpServletRequest`/`HttpServletResponse` and Ring's request/response maps.

## Dependencies (`pom.xml`)

```xml
<dependency>
    <groupId>io.github.rthadani</groupId>
    <artifactId>bff</artifactId>
    <version>0.1.0</version>
</dependency>
<dependency>
    <groupId>org.clojure</groupId>
    <artifactId>clojure</artifactId>
    <version>1.12.0</version>
</dependency>
```

Place the spec file in `src/main/resources/`.

## Controller

```java
import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.IMapEntry;
import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.PersistentHashMap;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

@Controller
public class BffController {

    private IFn handler;

    @PostConstruct
    public void init() {
        IFn require = Clojure.var("clojure.core", "require");
        require.invoke(Clojure.read("bff.core"));
        handler = (IFn) Clojure.var("bff.core", "create-handler")
                                .invoke("bff-spec.yaml");
    }

    @RequestMapping({"/graphql", "/graphiql"})
    public void handle(HttpServletRequest req, HttpServletResponse res) throws IOException {
        IPersistentMap ringResp = (IPersistentMap) handler.invoke(toRingRequest(req));
        writeResponse(ringResp, res);
    }

    private static IPersistentMap toRingRequest(HttpServletRequest req) throws IOException {
        Map<Object, Object> headers = new HashMap<>();
        Enumeration<String> names = req.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name.toLowerCase(), req.getHeader(name));
        }

        Map<Object, Object> m = new HashMap<>();
        m.put(Keyword.intern("server-port"),    req.getServerPort());
        m.put(Keyword.intern("server-name"),    req.getServerName());
        m.put(Keyword.intern("remote-addr"),    req.getRemoteAddr());
        m.put(Keyword.intern("uri"),            req.getRequestURI());
        m.put(Keyword.intern("query-string"),   req.getQueryString());
        m.put(Keyword.intern("scheme"),         Keyword.intern(req.getScheme()));
        m.put(Keyword.intern("request-method"), Keyword.intern(req.getMethod().toLowerCase()));
        m.put(Keyword.intern("protocol"),       req.getProtocol());
        m.put(Keyword.intern("headers"),        PersistentHashMap.create(headers));
        m.put(Keyword.intern("body"),           req.getInputStream());
        return PersistentHashMap.create(m);
    }

    private static void writeResponse(IPersistentMap resp, HttpServletResponse res) throws IOException {
        res.setStatus((Integer) resp.valAt(Keyword.intern("status")));

        IPersistentMap headers = (IPersistentMap) resp.valAt(Keyword.intern("headers"));
        if (headers != null) {
            for (Object entry : headers) {
                IMapEntry e = (IMapEntry) entry;
                res.setHeader((String) e.key(), (String) e.val());
            }
        }

        Object body = resp.valAt(Keyword.intern("body"));
        if (body instanceof String s) {
            res.getWriter().write(s);
        } else if (body instanceof InputStream is) {
            is.transferTo(res.getOutputStream());
        }
    }
}
```

## Registering extensions

Use a separate `@Component` to register transformers, validators, and resolvers
before the controller initialises. Add `@DependsOn("bffExtensions")` on
`BffController` if you need to guarantee ordering.

```java
import bff.executor.BaseTransformer;
import bff.executor.BaseResolver;
import bff.validator.BaseValidator;
import clojure.java.api.Clojure;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component("bffExtensions")
public class BffExtensions {

    @PostConstruct
    public void register() {
        Clojure.var("clojure.core", "require").invoke(Clojure.read("bff.executor"));
        Clojure.var("clojure.core", "require").invoke(Clojure.read("bff.validator"));

        Clojure.var("bff.executor", "register-transformer!")
               .invoke("attach-warnings", new WarningsTransformer());

        Clojure.var("bff.validator", "register-validator!")
               .invoke("check-order", new OrderValidator());

        Clojure.var("bff.executor", "register-resolver!")
               .invoke("user-profile", new UserProfileResolver());
    }

    static class WarningsTransformer extends BaseTransformer {
        @Override
        public Map<String, Object> transform(
                Map<String, Object> args,
                Map<String, StepResult> chainCtx,
                Map<String, Object> output) {
            List<String> warnings = new ArrayList<>();
            if (chainCtx.get("notify_user").isError()) {
                warnings.add("Notification could not be sent");
            }
            output.put("warnings", warnings);
            return output;
        }
    }

    static class OrderValidator extends BaseValidator {
        @Override
        public List<String> validate(Map<String, Object> args, Map<String, Object> ctx) {
            List<String> errors = new ArrayList<>();
            Double amount = (Double) args.get("amount");
            if (amount != null && amount > 10000) {
                errors.add("Orders above 10000 require manual approval");
            }
            return errors;
        }
    }

    static class UserProfileResolver extends BaseResolver {
        @Override
        public ResolverResult resolve(Map<String, Object> args, Map<String, Object> ctx) {
            return ResolverResult.ok(Map.of("fullName", "Alice", "email", "alice@example.com"));
        }
    }
}
```

See [extensions.md](extensions.md) for full documentation on each extension type.
