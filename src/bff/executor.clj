(ns bff.executor
  (:require [missionary.core :as m]
            [bff.http-client :as http]
            [bff.jq-engine :as jq]
            [bff.graph :as graph]
            [bff.error :as error]
            [bff.cache :as cache]
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
        headers   (->> (merge (select-keys request-ctx [:authorization :x-request-id
                                                        :x-correlation-id])
                              (:extra_headers step {}))
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

(defn- step->task
  [step args chain-ctx-atom request-ctx]
  (m/sp
    (let [ctx       @chain-ctx-atom
          condition (:condition step)
          skip?     (when condition
                      (not (resolve-value condition args ctx request-ctx)))]
      (when-not skip?
        (let [result (m/? (m/via m/blk
                                 (execute-step step args ctx request-ctx)))]
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


(defonce ^:private transformer-registry (atom {}))

(defn register-transformer!
  [k f]
  (swap! transformer-registry assoc k f))

(defn- resolve-transformer-fn [transformer]
  (if-let [k (:key transformer)]
    (or (get @transformer-registry k)
        (throw (ex-info (str "No transformer registered for key: " k)
                        {:key k :registered (keys @transformer-registry)})))
    (requiring-resolve (symbol (:ns transformer) (:fn transformer)))))

(defn- apply-transformer
  [transformer args chain-ctx mapped]
  (if transformer
    ((resolve-transformer-fn transformer) args chain-ctx mapped)
    mapped))

(defn run-endpoint
  "Build and execute the full endpoint pipeline.

   Returns a missionary task resolving to:
     {:data   {...}         ; the mapped output fields
      :errors [{...}]       ; any step errors (may be empty)}

   Partial failures are represented in :errors while :data contains
   whatever fields could be resolved from successful steps."
  [endpoint args request-ctx]
  (m/sp
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
       :errors errors})))
