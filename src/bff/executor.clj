(ns bff.executor
  (:require [missionary.core :as m]
            [bff.http-client :as http]
            [bff.jq-engine :as jq]
            [bff.graph :as graph]
            [bff.error :as error]
            [bff.cache :as cache]
            [bff.enricher :as enricher]
            [bff.interop :as interop]
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
         (remove (fn [[_ v]] (nil? v)))   ; drop unresolvable params
         (into {}))))

(defn- interpolate-url
  "Replace {param} placeholders in URL template."
  [url-template args chain-ctx]
  (str/replace url-template
               #"\{(\w+)\}"
               (fn [[_ k]]
                 (let [kw (keyword k)]
                   (str (or (get args kw)
                            ;; search completed step data for the key
                            (->> (vals chain-ctx)
                                 (some #(get (error/safe-data %) kw)))
                            ""))))))

(defn- execute-step
  "Execute one backend chain step. Returns a tagged result map.
   Never throws — errors are captured in the result."
  [step args chain-ctx request-ctx]
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
    (or (when cache-key (cache/lookup cache-key))
        (let [result (http/call {:method  method
                                 :url     url
                                 :params  params
                                 :body    body
                                 :headers headers
                                 :step-id step-id})]
          (when (and cache-key (= :ok (:status result)))
            (cache/save cache-key result (:ttl cache-cfg 60000)))
          result))))

(defn- execute-step-with-retry
  "Run a step and, if it declares a :retry policy, re-run it up to :max more
   times when the result's error code matches :on_code. An optional
   :before_retry hook is invoked between attempts and can rewrite the
   request-ctx (e.g. inject a refreshed auth token)."
  [step args chain-ctx request-ctx]
  (if-let [retry-cfg (:retry step)]
    (loop [attempt     0
           current-ctx request-ctx]
      (let [result (execute-step step args chain-ctx current-ctx)]
        (if (retry/should-retry? retry-cfg result attempt)
          (let [next-attempt (inc attempt)
                new-ctx (retry/apply-before-retry
                          retry-cfg
                          {:step-id     (keyword (:id step))
                           :attempt     next-attempt
                           :args        args
                           :chain-ctx   chain-ctx
                           :request-ctx current-ctx
                           :error       (:error result)})]
            (log/infof "Step [%s] retry #%d after %s"
                       (:id step) next-attempt (get-in result [:error :code]))
            (recur next-attempt new-ctx))
          result)))
    (execute-step step args chain-ctx request-ctx)))

(defn- step->task
  [step args chain-ctx-atom request-ctx]
  (m/sp
    (let [ctx       @chain-ctx-atom
          condition (:condition step)
          skip?     (when condition
                      (not (resolve-value condition args ctx request-ctx)))]
      (when-not skip?
        (let [result (m/? (m/via m/blk
                                 (execute-step-with-retry step args ctx request-ctx)))]
          (swap! chain-ctx-atom assoc (keyword (:id step)) result)
          result)))))

(defn- execute-wave
  [wave args chain-ctx-atom request-ctx]
  (if (= 1 (count wave))
    (step->task (first wave) args chain-ctx-atom request-ctx)
    (apply m/join
           (fn [& _results]
             @chain-ctx-atom)
           (map #(step->task % args chain-ctx-atom request-ctx) wave))))

(defn execute-graph
  "Execute the full backend_chain according to dependency waves.
   Returns a task that resolves to the final chain-ctx map.

   Execution model:
     • Steps with no unmet deps form a wave and run in PARALLEL (m/join)
     • Waves are executed SEQUENTIALLY
     • Step errors are captured in chain-ctx, never thrown
     • Critical steps (if declared) can abort via error/throw-if-critical!"
  [chain args request-ctx]
  (let [waves          (graph/execution-waves chain)
        chain-ctx-atom (atom {})
        critical-steps (keep (fn [s] (when (:critical s) (:id s))) chain)]
    (log/debugf "Execution plan: %s" (graph/wave-summary waves))
    (m/sp
      (doseq [wave waves]
        (m/? (execute-wave wave args chain-ctx-atom request-ctx))
        (error/throw-if-critical! @chain-ctx-atom critical-steps))
      @chain-ctx-atom)))

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

(defonce ^:private transformer-registry (atom {}))

(defn register-transformer!
  "Register a BffTransformer (or plain fn) under key k."
  [k transformer]
  (swap! transformer-registry assoc k transformer))

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

(defonce ^:private resolver-registry (atom {}))

(defn register-resolver!
  "Register a BffResolver (or plain fn) under key k.
   A registered resolver for an endpoint bypasses backend_chain entirely."
  [k resolver]
  (swap! resolver-registry assoc k resolver))

(defn- resolve-transformer [transformer]
  (if-let [k (:key transformer)]
    (or (get @transformer-registry k)
        (throw (ex-info (str "No transformer registered for key: " k)
                        {:key k :registered (keys @transformer-registry)})))
    (requiring-resolve (symbol (:ns transformer) (:fn transformer)))))

(defn- apply-transformer
  [transformer args chain-ctx mapped]
  (if transformer
    (transform (resolve-transformer transformer) args chain-ctx mapped)
    mapped))

(defn- resolve-resolver [resolver-cfg]
  (if-let [k (:key resolver-cfg)]
    (or (get @resolver-registry k)
        (throw (ex-info (str "No resolver registered for key: " k)
                        {:key k :registered (keys @resolver-registry)})))
    (requiring-resolve (symbol (:ns resolver-cfg) (:fn resolver-cfg)))))

(defn run-endpoint
  "Build and execute the full endpoint pipeline.

   Returns a missionary task resolving to:
     {:data   {...}         ; the mapped output fields
      :errors [{...}]       ; any step errors (may be empty)}

   If the endpoint has a :resolver key, it is called instead of the
   backend_chain. The resolver owns the full {:data :errors} response.

   Partial failures are represented in :errors while :data contains
   whatever fields could be resolved from successful steps."
  [endpoint args request-ctx]
  (m/sp
    (let [request-ctx (enricher/enrich-ctx request-ctx)]
      (if-let [val-errors (validator/run-validation endpoint args request-ctx)]
        {:data nil :errors val-errors}
        (if-let [resolver-cfg (:resolver endpoint)]
          (resolve-endpoint (resolve-resolver resolver-cfg) args request-ctx)
          (let [chain-ctx (m/? (execute-graph (:backend_chain endpoint)
                                              args
                                              request-ctx))
                errors    (error/step-errors chain-ctx)
                mapped    (apply-output-mapping (:output_mapping endpoint)
                                                args
                                                chain-ctx
                                                request-ctx)
                final     (apply-transformer (:transformer endpoint)
                                             args
                                             chain-ctx
                                             mapped)]
            {:data   final
             :errors errors}))))))
