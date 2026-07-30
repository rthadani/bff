package io.github.rthadani.bff;

import clojure.lang.IFn;
import clojure.lang.IMapEntry;
import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.PersistentHashMap;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/**
 * Bridges a Jakarta Servlet 6 request into the Ring request map that the
 * underlying BFF handler expects, then writes the Ring response back onto the
 * servlet's output stream.
 *
 * <p>Prefer constructing via {@link Bff#createServlet(String)}. The public
 * constructor exists for tests and for cases where you want to wrap a
 * hand-built handler.
 */
public class BffServlet extends HttpServlet {

    private final IFn handler;

    public BffServlet(IFn handler) {
        this.handler = handler;
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {
        IPersistentMap ringResp = (IPersistentMap) handler.invoke(toRingRequest(req));
        writeResponse(ringResp, res);
    }

    /** Build a Ring request map from a Jakarta servlet request. Public so
     *  callers can unit-test the bridge without a servlet container. */
    public static IPersistentMap toRingRequest(HttpServletRequest req) throws IOException {
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

    /** Write a Ring response map onto a Jakarta servlet response. Public so
     *  callers can unit-test the bridge without a servlet container. */
    public static void writeResponse(IPersistentMap resp, HttpServletResponse res) throws IOException {
        Object status = resp.valAt(Keyword.intern("status"));
        if (status instanceof Integer i) res.setStatus(i);

        IPersistentMap headers = (IPersistentMap) resp.valAt(Keyword.intern("headers"));
        if (headers != null) {
            for (Object entry : headers) {
                IMapEntry e = (IMapEntry) entry;
                res.setHeader((String) e.key(), String.valueOf(e.val()));
            }
        }

        Object body = resp.valAt(Keyword.intern("body"));
        if (body instanceof String s) {
            res.getWriter().write(s);
        } else if (body instanceof InputStream is) {
            try (is) { is.transferTo(res.getOutputStream()); }
        }
    }
}
