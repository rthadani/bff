(ns bff.graph
  (:require [clojure.string :as str]))

(defn- build-dep-graph
  [chain]
  (->> chain
       (map (fn [step]
              [(keyword (:id step))
               (set (map keyword (:deps step [])))]))
       (into {})))

(defn- validate-deps!
  [chain]
  (let [all-ids (set (map #(keyword (:id %)) chain))]
    (doseq [step chain
            dep  (:deps step [])]
      (when-not (contains? all-ids (keyword dep))
        (throw (ex-info (str "Step '" (:id step)
                             "' declares unknown dep '" dep "'")
                        {:step (:id step) :dep dep}))))))

(defn execution-waves
  [chain]
  (validate-deps! chain)
  (let [dep-graph (build-dep-graph chain)
        step-map  (->> chain
                       (map (fn [s] [(keyword (:id s)) s]))
                       (into {}))]
    (loop [remaining dep-graph
           completed #{}
           waves     []]
      (if (empty? remaining)
        waves
        (let [ready (->> remaining
                         (filter (fn [[_id deps]]
                                   (every? completed deps)))
                         (map first)
                         set)]
          (when (empty? ready)
            (throw (ex-info "Circular dependency detected in backend_chain"
                            {:remaining (keys remaining)})))
          (recur (apply dissoc remaining ready)
                 (into completed ready)
                 (conj waves (mapv step-map ready))))))))

(defn wave-summary
  [waves]
  (->> waves
       (map-indexed (fn [i wave]
                      (str "Wave " i ": ["
                           (str/join ", " (map :id wave))
                           "]")))
       (str/join " → ")))
