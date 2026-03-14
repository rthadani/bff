(ns bff.examples.transformers.user
  (:require [clojure.string :as str]))

(defn enrich-dashboard
  "args        — GraphQL input args
   chain-ctx   — map of step-id → tagged result {:status :ok :data {...}}
   output      — already-mapped output fields

   Returns the final output map (can add, remove, or transform fields)."
  [_args _chain-ctx output]
  (cond-> output
    ;; Normalize fullName to title case
    (:fullName output)
    (update :fullName
            (fn [n]
              (->> (str/split n #"\s+")
                   (map str/capitalize)
                   (str/join " "))))

    ;; Ensure activityCount is never nil
    (nil? (:activityCount output))
    (assoc :activityCount 0)

    ;; Ensure recentTitles is never nil
    (nil? (:recentTitles output))
    (assoc :recentTitles [])))
