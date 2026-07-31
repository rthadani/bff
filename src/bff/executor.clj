(ns bff.executor
  (:require [missionary.core :as m]
            [bff.http-client :as http]
            [bff.jq-engine :as jq]
            [bff.graph :as graph]
            [bff.error :as error]
            [bff.cache :as cache]
            [bff.enricher :as enricher]
            [bff.interop :as interop]
            [bff.registry :as registry]
            [bff.retry :as retry]
            [bff.validator :as validator]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

(defn- resolve-value
  [mapping args chain-ctx request-ctx]
  (case (keyword (:source mapping))

    :args
    (get args (keyword (:key mapping)))

    :step
    (let [step-result (get chain-ctx (keyword (:step_id mapping)))
          data        (error/safe-data step-result)]
      (when data
        (if-let [q (:compiled-jq mapping)]
          (jq/execute q data)
          (get data (keyword (:key mapping))))))

    :value
    (:value mapping)

    :ctx
    (get request-ctx (keyword (:key mapping)))

    nil))

(defn- resolve-param-map
  "Resolve all entries in a param/body mapping map."
  [param-mapping args chain-ctx request-ctx]
  (when (seq param-mapping)
    (->> param-mapping
         (map (fn [[k v]]
                [k (resolve-value v args chain-ctx request-ctx)]))
         (remove (fn [[_ v]] (nil? v)))
         (into {}))))

(defn- interpolate-url
  "Replace {param} placeholders in URL template."
  [url-template args chain-ctx]
  (str/replace url-template
               #"\{(\w+)\}"
               (fn [[_ k]]
                 (let [kw (keyword k)]
                   (str (or (get args kw)
                            (->> (vals chain-ctx)
                                 (some #(get (error/safe-data %) kw)))
                            ""))))))

(defn- execute-step
  "Execute one backend chain step. Returns a tagged result map.
   Never throws — errors are captured in the result."
  [step args chain-ctx request-ctx cache-store]
  (let [step-id   (keyword (:id step))
        url       (interpolate-url (:url step) args chain-ctx)
        method    (keyword (str/lower-case (:method step "GET")))
        params    (resolve-param-map (:input_mapping step) args chain-ctx request-ctx)
        body      (resolve-param-map (:body_mapping step) args chain-ctx request-ctx)
        headers   (->> (merge request-ctx (:extra_headers step {}))
                       (remove (fn [[_ v]] (nil? v)))
                       (into {} (map (fn [[k v]] [(name k) v]))))
        cache-cfg (:cache step)
        cache-key (when cache-cfg
                    (interpolate-url (:key cache-cfg) args chain-ctx))]
    (log/infof "Step [%s] → %s %s" (name step-id) (str/upper-case (name method)) url)
    (or (when cache-key (cache/lookup cache-store cache-key))
        (let [result (http/call {:method  method
                                 :url     url
                                 :params  params
                                 :body    body
                                 :headers headers
                                 :step-id step-id})]
          (when (and cache-key (= :ok (:status result)))
            (cache/save cache-store cache-key result (:ttl cache-cfg 60000)))
          result))))

(defn- execute-step-with-retry
  "Run a step and, if it declares a :retry policy, re-run it up to :max more
   times when the result's error code matches :on_code. An optional
   :before_retry hook is invoked between attempts and can rewrite the
   request-ctx (e.g. inject a refreshed auth token).

   `extensions` supplies :cache and :retry-hooks."
  [step args chain-ctx request-ctx extensions]
  (let [{:keys [cache retry-hooks]} extensions]
    (if-let [retry-cfg (:retry step)]
      (loop [attempt     0
             current-ctx request-ctx]
        (let [result (execute-step step args chain-ctx current-ctx cache)]
          (if (retry/should-retry? retry-cfg result attempt)
            (let [next-attempt (inc attempt)
                  new-ctx (retry/apply-before-retry
                            retry-cfg
                            {:step-id     (keyword (:id step))
                             :attempt     next-attempt
                             :args        args
                             :chain-ctx   chain-ctx
                             :request-ctx current-ctx
                             :error       (:error result)}
                            retry-hooks)]
              (log/infof "Step [%s] retry #%d after %s"
                         (:id step) next-attempt (get-in result [:error :code]))
              (recur next-attempt new-ctx))
            result)))
      (execute-step step args chain-ctx request-ctx cache))))

(defn- apply-error-mapping
  "If the step declares an :errors map and the result is an error, remap
   :code to a domain-specific one. The mapping is looked up by (a) the raw
   HTTP status code, (b) the semantic code keyword, or (c) the name-string
   of the semantic code — in that order. The value replaces the original
   :code as-is (typically a string like \"MAC_ALREADY_MAPPED\").

   Applied *after* retry so retry decisions still use the semantic codes."
  [step result]
  (if-let [mapping (and (= :error (:status result)) (:errors step))]
    (let [semantic (get-in result [:error :code])
          mapped   (or (get mapping (:http-status result))
                       (get mapping semantic)
                       (get mapping (some-> semantic name)))]
      (cond-> result mapped (assoc-in [:error :code] mapped)))
    result))

(defn- step->task
  "Run one step against an immutable chain-ctx. Returns a map with the step's
   result, or nil if the step was skipped by its :condition.

     {:step-id      :keyword
      :result       {:status :ok/:error ...}
      :compensation {...}}      ; only present when the step succeeded and
                                ;   declared a :compensation config"
  [step args chain-ctx request-ctx extensions]
  (m/sp
    (let [condition (:condition step)
          skip?     (when condition
                      (not (resolve-value condition args chain-ctx request-ctx)))]
      (when-not skip?
        (let [raw    (m/? (m/via m/blk
                                 (execute-step-with-retry step args chain-ctx request-ctx extensions)))
              result (apply-error-mapping step raw)]
          (cond-> {:step-id (keyword (:id step)) :result result}
            (and (= :ok (:status result)) (:compensation step))
            (assoc :compensation (:compensation step))))))))

(defn- execute-wave
  "Run every step in the wave in parallel and return a seq of step->task
   results with skipped (nil) entries removed."
  [wave args chain-ctx request-ctx extensions]
  (apply m/join
         (fn [& results] (into [] (filter some?) results))
         (map #(step->task % args chain-ctx request-ctx extensions) wave)))

(defn- run-compensations
  "Execute recorded compensations in reverse order. Each compensation is a
   mini step (url + method + input_mapping + body_mapping + extra_headers)
   that runs against the final chain-ctx. Errors are logged and swallowed —
   compensation failure never masks the original chain failure."
  [compensations args chain-ctx request-ctx extensions]
  (when (seq compensations)
    (log/infof "Chain failed — running %d compensation(s) in reverse order"
               (count compensations)))
  (doseq [{:keys [step-id compensation]} (reverse compensations)]
    (try
      (execute-step (assoc compensation :id (str "compensate-" (name step-id)))
                    args chain-ctx request-ctx (:cache extensions))
      (catch Exception e
        (log/warnf "Compensation for step [%s] failed: %s"
                   step-id (.getMessage e))))))

(defn execute-graph
  "Execute the full backend_chain according to dependency waves.
   Returns a task that resolves to the final chain-ctx map.

   `extensions` is the caller-owned map with :cache and :retry-hooks.

   Steps that succeed *and* declare a :compensation config are recorded;
   if a later critical step fails, the recorded compensations run in
   reverse order before the failure is re-thrown to the caller."
  [chain args request-ctx extensions]
  (let [waves          (graph/execution-waves chain)
        critical-steps (keep (fn [s] (when (:critical s) (:id s))) chain)]
    (log/debugf "Execution plan: %s" (graph/wave-summary waves))
    (m/sp
      (loop [remaining     waves
             chain-ctx     {}
             compensations []]
        (if (empty? remaining)
          chain-ctx
          (let [results       (m/? (execute-wave (first remaining) args chain-ctx request-ctx extensions))
                chain-ctx     (into chain-ctx
                                    (map (juxt :step-id :result))
                                    results)
                compensations (into compensations
                                    (keep (fn [{:keys [step-id compensation]}]
                                            (when compensation
                                              {:step-id step-id :compensation compensation})))
                                    results)]
            (try
              (error/throw-if-critical! chain-ctx critical-steps)
              (catch clojure.lang.ExceptionInfo e
                (run-compensations compensations args chain-ctx request-ctx extensions)
                (throw e)))
            (recur (rest remaining) chain-ctx compensations)))))))

(defn- apply-output-mapping
  [output-mapping args chain-ctx request-ctx]
  (->> output-mapping
       (map (fn [[field mapping]]
              [field (if (:source mapping)
                       (resolve-value mapping args chain-ctx request-ctx)
                       (apply-output-mapping mapping args chain-ctx request-ctx))]))
       (into {})))


(defprotocol BffTransformer
  (transform [this args chain-ctx mapped]))

;; Plain fns satisfy BffTransformer via IFn.
(extend-protocol BffTransformer
  clojure.lang.IFn
  (transform [f args chain-ctx mapped]
    (f args chain-ctx mapped)))

;; Java implementations of io.github.rthadani.bff.BffTransformer are first-class.
(extend-type io.github.rthadani.bff.BffTransformer
  BffTransformer
  (transform [this args chain-ctx mapped]
    (interop/->clj
     (.transform this
                 (interop/->java args)
                 (interop/->java chain-ctx)
                 (interop/->java mapped)))))

(defprotocol BffResolver
  (resolve-endpoint [this args ctx]))

;; Plain fns satisfy BffResolver via IFn.
(extend-protocol BffResolver
  clojure.lang.IFn
  (resolve-endpoint [f args ctx]
    (f args ctx)))

;; Java implementations of io.github.rthadani.bff.BffResolver are first-class.
(extend-type io.github.rthadani.bff.BffResolver
  BffResolver
  (resolve-endpoint [this args ctx]
    (interop/->clj
     (.resolve this
               (interop/->java args)
               (interop/->java ctx)))))

(defn- apply-transformer
  [transformer-cfg args chain-ctx mapped transformers]
  (if transformer-cfg
    (transform (registry/resolve-impl transformer-cfg transformers "transformer")
               args chain-ctx mapped)
    mapped))

(defn run-endpoint
  "Build and execute the full endpoint pipeline.

   Returns a missionary task resolving to:
     {:data   {...}         ; the mapped output fields
      :errors [{...}]       ; any step errors (may be empty)}

   `extensions` is the caller-owned config map. Keys (all optional):
     :enrichers    — ordered seq of context enrichers
     :validators   — {\"key\" impl} for :validator {:key ...} refs
     :transformers — {\"key\" impl}
     :resolvers    — {\"key\" impl}
     :retry-hooks  — {\"key\" impl}
     :cache        — a CacheStore, or nil

   If the endpoint has a :resolver key, it is called instead of the
   backend_chain. Partial failures are represented in :errors while :data
   contains whatever fields could be resolved from successful steps."
  [endpoint args request-ctx extensions]
  (m/sp
    (let [request-ctx (enricher/enrich-ctx request-ctx (:enrichers extensions))]
      (if-let [val-errors (validator/run-validation endpoint args request-ctx
                                                    (:validators extensions))]
        {:data nil :errors val-errors}
        (if-let [resolver-cfg (:resolver endpoint)]
          (resolve-endpoint (registry/resolve-impl resolver-cfg
                                                   (:resolvers extensions)
                                                   "resolver")
                            args request-ctx)
          (let [chain-ctx (m/? (execute-graph (:backend_chain endpoint)
                                              args
                                              request-ctx
                                              extensions))
                errors    (error/step-errors chain-ctx)
                mapped    (apply-output-mapping (:output_mapping endpoint)
                                                args
                                                chain-ctx
                                                request-ctx)
                final     (apply-transformer (:transformer endpoint)
                                             args
                                             chain-ctx
                                             mapped
                                             (:transformers extensions))]
            {:data   final
             :errors errors}))))))
