(ns bff.retry
  "Step-level retry with an optional pre-retry hook.

   A step declares retry behaviour under its `:retry` key:

       :retry {:max 2
               :on_code [:unauthorized]
               :before_retry {:key \"cmap-token-refresh\"}}

   The hook is called before each retry attempt with a failure-context map;
   its return value replaces the request-ctx used for the next call. Common
   use case: refresh a bearer token and inject it into the Authorization
   header before the retry.")

(defprotocol BffRetryHook
  (before-retry [this failure-context]
    "Called before a retry attempt. `failure-context` has :step-id, :attempt
     (1-indexed retry number), :args, :chain-ctx, :request-ctx, :error.
     Return a request-ctx map to use for the retry, or nil to reuse the
     current one."))

;; Plain fns satisfy BffRetryHook via IFn.
(extend-protocol BffRetryHook
  clojure.lang.IFn
  (before-retry [f ctx] (f ctx)))

(defonce ^:private hook-registry (atom {}))

(defn register-retry-hook!
  "Register a BffRetryHook (or plain fn) under key k."
  [k hook]
  (swap! hook-registry assoc k hook))

(defn- resolve-hook [hook-cfg]
  (if-let [k (:key hook-cfg)]
    (or (get @hook-registry k)
        (throw (ex-info (str "No retry hook registered for key: " k)
                        {:key k :registered (keys @hook-registry)})))
    (requiring-resolve (symbol (:ns hook-cfg) (:fn hook-cfg)))))

(defn- code-matches?
  [result on-codes]
  (let [code (get-in result [:error :code])]
    (some #(= code (keyword %)) on-codes)))

(defn should-retry?
  "True if the step is configured to retry, the result is an error, its code
   is in the allowlist, and the retry budget isn't exhausted. `attempt` is
   the count of retries already made (0 = the first call just failed)."
  [retry-cfg result attempt]
  (boolean
    (and retry-cfg
         (= :error (:status result))
         (code-matches? result (:on_code retry-cfg))
         (< attempt (:max retry-cfg 0)))))

(defn apply-before-retry
  "Run the before-retry hook if declared. Returns the request-ctx to use for
   the next attempt (either the hook's return value or the current ctx)."
  [retry-cfg failure-context]
  (if-let [hook-cfg (:before_retry retry-cfg)]
    (let [hook (resolve-hook hook-cfg)]
      (or (before-retry hook failure-context)
          (:request-ctx failure-context)))
    (:request-ctx failure-context)))
