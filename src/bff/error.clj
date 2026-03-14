(ns bff.error
  (:require [com.walmartlabs.lacinia.resolve :as resolve]))

(defn error? [result] (= :error (:status result)))

(defn step-errors
  [chain-ctx]
  (->> chain-ctx
       (filter (fn [[_id result]]
                 (= :error (:status result))))
       (map (fn [[step-id {:keys [error]}]]
              {:message   (:message error "Backend step failed")
               :extensions {:code    (:code error :unknown)
                            :step    (name step-id)
                            :detail  (:detail error)}}))))

(defn throw-if-critical!
  [chain-ctx critical-steps]
  (doseq [step-id critical-steps]
    (let [result (get chain-ctx (keyword step-id))]
      (when (= :error (:status result))
        (throw (ex-info "Critical step failed"
                        {:step   step-id
                         :error  (:error result)}))))))

(defn safe-data
  [result]
  (when (= :ok (:status result))
    (:data result)))

(defn wrap-resolver-errors
  [resolver-fn]
  (fn [ctx args val]
    (try
      (resolver-fn ctx args val)
      (catch clojure.lang.ExceptionInfo e
        (resolve/resolve-as
         nil
         {:message    (ex-message e)
          :extensions {:code   :execution-error
                       :detail (ex-data e)}}))
      (catch Exception e
        (resolve/resolve-as
         nil
         {:message    (.getMessage e)
          :extensions {:code :internal-error}})))))
