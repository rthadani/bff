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

   Enrichers run in registration order; each sees the accumulated ctx."
  (:require [bff.interop :as interop]))

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

(defonce ^:private enrichers (atom []))

(defn register-enricher!
  "Append an enricher to the ordered chain. Enrichers run in registration
   order; each sees the ctx after every earlier enricher has run."
  [enricher]
  (swap! enrichers conj enricher))

(defn reset-enrichers!
  "Clear all registered enrichers. Primarily for tests."
  []
  (reset! enrichers []))

(defn enrich-ctx
  "Fold every registered enricher over ctx. An enricher's return value
   is merged into the accumulated ctx; nil or a non-map return is
   ignored."
  [ctx]
  (reduce
    (fn [acc enricher]
      (let [added (enrich enricher acc)]
        (if (map? added) (merge acc added) acc)))
    (or ctx {})
    @enrichers))
