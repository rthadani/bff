(ns bff.examples.validators.orders
  "Example custom validator wired into the demo spec by key.")

(defn check-order
  "Cross-arg validator for createOrder: reject empty item lists and priority
   values outside the supported set. Built-in per-arg rules (regex, min/max)
   handle the simpler cases already."
  [args _ctx]
  (cond-> []
    (empty? (:items args))
    (conj {:message "createOrder requires at least one line item"})

    (and (:priority args)
         (not (#{"normal" "urgent" "back-office"} (:priority args))))
    (conj {:message (str "priority must be one of normal, urgent, back-office — got "
                         (:priority args))})

    :always not-empty))
