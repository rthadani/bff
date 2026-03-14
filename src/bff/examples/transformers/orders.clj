(ns bff.examples.transformers.orders
  (:require [bff.error :as error]))

(defn attach-warnings
  "Attach human-readable warnings for non-critical step failures."
  [_args chain-ctx output]
  (let [warnings
        (cond-> []
          (error/error? (get chain-ctx :notify_user))
          (conj "Order confirmed but notification could not be sent.")

          (error/error? (get chain-ctx :update_inventory))
          (conj "Order confirmed but inventory reservation failed — ops team notified."))]
    (assoc output :warnings (not-empty warnings))))
