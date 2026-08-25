(ns bff.enricher
  "Per-request context enrichment.

   An enricher is a fn / protocol impl that runs once per GraphQL operation
   before validators and the backend chain. It receives the current request
   ctx (headers plus any values added by earlier enrichers) and returns a
   map of new values to merge in.

   Typical use case in a Spring Boot app: pull the customer / equipment
   identifiers out of Redis using the JWT subject, so every step and
   validator downstream can read them from ctx without repeating the
   lookup.

   Enrichers are supplied to `bff.core/create-handler` as an ordered
   sequence; each sees the accumulated ctx from earlier enrichers."
  (:require [bff.interop :as interop]
            [taoensso.timbre :as log]))

(defprotocol BffContextEnricher
  (enrich [this ctx]
    "Return a map of additional key/value pairs to merge into ctx, or nil
     to leave ctx unchanged."))

;; Plain fns satisfy BffContextEnricher via IFn.
(extend-protocol BffContextEnricher
  clojure.lang.IFn
  (enrich [f ctx] (f ctx)))

;; Java implementations of io.github.rthadani.bff.BffContextEnricher are first-class.
(extend-type io.github.rthadani.bff.BffContextEnricher
  BffContextEnricher
  (enrich [this ctx]
    (some-> (.enrich this (interop/->java ctx))
            interop/->clj)))

(defn enrich-ctx
  "Fold `enrichers` (an ordered seq) over `ctx`. An enricher's return value
   is merged into the accumulated ctx; nil or a non-map return is ignored."
  [ctx enrichers]
  (reduce
    (fn [acc enricher]
      (let [added (enrich enricher acc)]
        (if (map? added)
          (do (log/debugf "Enricher added keys: %s" (keys added))
              (merge acc added))
          acc)))
    (or ctx {})
    (or enrichers [])))
