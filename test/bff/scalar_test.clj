(ns bff.scalar-test
  (:require [clojure.test :refer [deftest is testing]]
            [bff.scalar :as scalar]
            [bff.schema-builder :as sb]
            [com.walmartlabs.lacinia :as lacinia]
            [missionary.core :as m]
            [bff.executor :as executor]))

;; ---------------------------------------------------------------------------
;; Protocol — map form, Java-interface form, and IFn form
;; ---------------------------------------------------------------------------

(deftest test-map-form-scalar-parse-and-serialize
  (let [s {:parse     #(Integer/parseInt (str %))
           :serialize str}]
    (is (= 42     (scalar/parse s "42")))
    (is (= "42"   (scalar/serialize s 42)))))

(deftest test-java-interface-scalar
  (let [s (reify io.github.rthadani.bff.BffScalar
            (parse     [_ v] (Integer/parseInt (str v)))
            (serialize [_ v] (str v)))]
    (is (= 99   (scalar/parse s "99")))
    (is (= "99" (scalar/serialize s 99)))))

;; ---------------------------------------------------------------------------
;; Built-in date/time scalars
;; ---------------------------------------------------------------------------

(deftest test-date-time-roundtrip
  (let [now (java.time.Instant/parse "2026-07-30T18:00:00Z")]
    (is (= now (scalar/parse    scalar/date-time "2026-07-30T18:00:00Z")))
    (is (= "2026-07-30T18:00:00Z" (scalar/serialize scalar/date-time now)))))

(deftest test-date-roundtrip
  (let [d (java.time.LocalDate/of 2026 7 30)]
    (is (= d          (scalar/parse    scalar/date "2026-07-30")))
    (is (= "2026-07-30" (scalar/serialize scalar/date d)))))

(deftest test-local-date-time-roundtrip
  (let [dt (java.time.LocalDateTime/of 2026 7 30 18 0 0)]
    (is (= dt (scalar/parse    scalar/local-date-time "2026-07-30T18:00:00")))
    (is (= "2026-07-30T18:00" (scalar/serialize scalar/local-date-time dt)))))

(deftest test-uuid-roundtrip
  (let [u (java.util.UUID/fromString "11111111-2222-3333-4444-555555555555")]
    (is (= u (scalar/parse    scalar/uuid "11111111-2222-3333-4444-555555555555")))
    (is (= "11111111-2222-3333-4444-555555555555"
           (scalar/serialize scalar/uuid u)))))

(deftest test-date-time-rejects-garbage
  (is (thrown? Exception (scalar/parse scalar/date-time "not-a-date"))))

;; ---------------------------------------------------------------------------
;; schema_builder wiring — spec declares a scalar, config supplies the impl
;; ---------------------------------------------------------------------------

(def ^:private with-datetime-spec
  {:scalars   [{:name "DateTime" :description "ISO-8601 timestamp"}]
   :endpoints [{:name  "echo"
                :type  "query"
                :args  {:when {:type "DateTime!"}}
                :output_type   {:name "EchoResult" :fields {:when "DateTime!"}}
                :backend_chain []
                :output_mapping {}}]})

(deftest test-scalar-declared-in-spec-but-no-impl-throws
  (is (thrown? clojure.lang.ExceptionInfo
               (sb/build-schema with-datetime-spec {}))))

(deftest test-scalar-declared-in-spec-with-impl-compiles
  (let [schema (sb/build-schema with-datetime-spec
                                {:scalars {"DateTime" scalar/date-time}})]
    (is (some? schema))))

(deftest test-scalar-parse-runs-on-arg-input
  (let [received (atom nil)
        schema   (sb/build-schema with-datetime-spec
                                  {:scalars {"DateTime" scalar/date-time}})]
    (with-redefs [executor/run-endpoint
                  (fn [_ args _ _ _]
                    (reset! received args)
                    (m/sp {:data {:when (:when args)} :errors []}))]
      (lacinia/execute schema
                       "query($when: DateTime!) { echo(when: $when) { when } }"
                       {:when "2026-07-30T18:00:00Z"}
                       {})
      (is (instance? java.time.Instant (:when @received))))))

(deftest test-scalar-serialize-runs-on-output
  (let [schema (sb/build-schema with-datetime-spec
                                {:scalars {"DateTime" scalar/date-time}})]
    (with-redefs [executor/run-endpoint
                  (fn [_ args _ _ _]
                    (m/sp {:data {:when (java.time.Instant/parse "2026-07-30T18:00:00Z")}
                           :errors []}))]
      (let [result (lacinia/execute schema
                                    "query($when: DateTime!) { echo(when: $when) { when } }"
                                    {:when "2026-07-30T18:00:00Z"}
                                    {})]
        (is (= "2026-07-30T18:00:00Z" (get-in result [:data :echo :when])))))))

(deftest test-no-scalars-in-spec-schema-still-builds
  (let [spec {:endpoints [{:name "ping" :type "query" :args {}
                           :output_type {:name "Ping" :fields {:msg "String!"}}
                           :backend_chain [] :output_mapping {}}]}]
    (is (some? (sb/build-schema spec {})))))
