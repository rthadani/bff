(ns bff.scalar
  "Custom GraphQL scalar types.

   A scalar declared in the spec's top-level `scalars:` list must have a
   matching implementation in the handler's :scalars extension map. Each
   implementation provides two fns:

     • parse:     input from client (string / number / etc.) → internal value.
                  Throws on invalid input.
     • serialize: internal value → JSON primitive
                  (String / Number / Boolean / nil).

   The engine wires these into Lacinia's :scalars schema section at
   build-schema time.

   Built-in convenience scalars for common date/time types are provided —
   {@code bff.scalar/date-time}, {@code bff.scalar/date},
   {@code bff.scalar/local-date-time} — that users can drop into their config
   without writing parse/serialize themselves."
  (:require [bff.interop :as interop]))

(defprotocol BffScalar
  (parse     [this value] "any → internal value; throw on invalid input")
  (serialize [this value] "internal value → JSON primitive"))

;; A map with :parse and :serialize keys satisfies BffScalar. This is the
;; ergonomic Clojure form — no defrecord ceremony.
(extend-protocol BffScalar
  clojure.lang.APersistentMap
  (parse     [m v] ((:parse m) v))
  (serialize [m v] ((:serialize m) v)))

;; Java implementations of io.github.rthadani.bff.BffScalar are first-class.
;; Input and output are opaque Objects — scalars serialize to primitives,
;; not maps, so no interop conversion is needed.
(extend-type io.github.rthadani.bff.BffScalar
  BffScalar
  (parse     [this v] (.parse this v))
  (serialize [this v] (.serialize this v)))

;; ---------------------------------------------------------------------------
;; Built-in date/time scalars
;; ---------------------------------------------------------------------------

(def date-time
  "GraphQL scalar for ISO-8601 timestamps, backed by java.time.Instant.
   Round-trips \"2026-07-30T18:00:00Z\" ↔ Instant."
  {:parse     (fn [v] (java.time.Instant/parse (str v)))
   :serialize str})

(def date
  "GraphQL scalar for ISO-8601 calendar dates, backed by java.time.LocalDate.
   Round-trips \"2026-07-30\" ↔ LocalDate."
  {:parse     (fn [v] (java.time.LocalDate/parse (str v)))
   :serialize str})

(def local-date-time
  "GraphQL scalar for naive date-times (no zone), backed by
   java.time.LocalDateTime. Round-trips \"2026-07-30T18:00:00\" ↔
   LocalDateTime."
  {:parse     (fn [v] (java.time.LocalDateTime/parse (str v)))
   :serialize str})

(def uuid
  "GraphQL scalar for RFC 4122 UUIDs, backed by java.util.UUID."
  {:parse     (fn [v] (java.util.UUID/fromString (str v)))
   :serialize str})
