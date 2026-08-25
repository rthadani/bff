(ns bff.enricher-test
  (:require [clojure.test :refer [deftest is testing]]
            [bff.enricher :as enricher]
            [bff.executor :as executor]
            [bff.http-client :as http]))

(defn- run-sync! [task]
  (let [p (java.util.concurrent.CompletableFuture.)]
    (task #(.complete p %) #(.completeExceptionally p %))
    (try
      (.get p)
      (catch java.util.concurrent.ExecutionException e
        (throw (.getCause e))))))

(def ^:private base-step
  {:id "s" :url "http://api" :method "GET" :deps []})

(def ^:private base-endpoint
  {:backend_chain [base-step] :output_mapping {}})

;; ---------------------------------------------------------------------------
;; enrich-ctx: pure function
;; ---------------------------------------------------------------------------

(deftest test-empty-enrichers-ctx-unchanged
  (is (= {:a 1} (enricher/enrich-ctx {:a 1} []))))

(deftest test-nil-ctx-becomes-empty-map
  (is (= {} (enricher/enrich-ctx nil []))))

(deftest test-single-enricher-adds-keys
  (is (= {:authorization "tok" :customerId "c1"}
         (enricher/enrich-ctx {:authorization "tok"}
                              [(fn [_] {:customerId "c1"})]))))

(deftest test-enricher-returning-nil-leaves-ctx-alone
  (is (= {:a 1} (enricher/enrich-ctx {:a 1} [(fn [_] nil)]))))

(deftest test-enricher-returning-non-map-ignored
  (is (= {:a 1} (enricher/enrich-ctx {:a 1} [(fn [_] "not-a-map")]))))

(deftest test-multiple-enrichers-run-in-order
  (is (= {:step-1 true :step-2 true}
         (enricher/enrich-ctx
           {}
           [(fn [_]   {:step-1 true})
            (fn [ctx] {:step-2 (:step-1 ctx)})]))))

(deftest test-later-enricher-overwrites-earlier-key
  (is (= "second" (:tag (enricher/enrich-ctx
                          {}
                          [(fn [_] {:tag "first"})
                           (fn [_] {:tag "second"})])))))

;; ---------------------------------------------------------------------------
;; Java interface — io.github.rthadani.bff.BffContextEnricher
;; ---------------------------------------------------------------------------

(deftest test-java-interface-enricher
  (let [impl (reify io.github.rthadani.bff.BffContextEnricher
               (enrich [_ ctx]
                 (doto (java.util.HashMap.)
                   (.put "customerId" (str "cust-for-" (.get ^java.util.Map ctx "userId"))))))]
    (is (= "cust-for-u42"
           (:customerId (enricher/enrich-ctx {:userId "u42"} [impl]))))))

(deftest test-java-interface-null-return-preserves-ctx
  (let [impl (reify io.github.rthadani.bff.BffContextEnricher
               (enrich [_ _ctx] nil))]
    (is (= {:a 1} (enricher/enrich-ctx {:a 1} [impl])))))

;; ---------------------------------------------------------------------------
;; Integration — enrichers via run-endpoint's extensions arg
;; ---------------------------------------------------------------------------

(deftest test-enricher-values-visible-to-validator
  (let [seen (atom nil)]
    (with-redefs [http/call (fn [_ _] (http/ok {}))]
      (run-sync!
        (executor/run-endpoint
          (assoc base-endpoint :validator {:key "capture-ctx"})
          {} {:authorization "tok"}
          {:enrichers  [(fn [_] {:customerId "c99"})]
           :validators {"capture-ctx" (fn [_args ctx] (reset! seen ctx) nil)}})))
    (is (= "c99"  (:customerId @seen)))
    (is (= "tok"  (:authorization @seen)))))

(deftest test-enricher-values-forwarded-to-backend-step-headers
  (let [seen-headers (atom nil)]
    (with-redefs [http/call (fn [_ {:keys [headers]}]
                              (reset! seen-headers headers)
                              (http/ok {}))]
      (run-sync!
        (executor/run-endpoint
          base-endpoint {} {:authorization "tok"}
          {:enrichers [(fn [_] {:x-customer-id "cust-42"})]})))
    (is (= "cust-42" (get @seen-headers "x-customer-id")))
    (is (= "tok"     (get @seen-headers "authorization")))))

(deftest test-enricher-values-visible-via-ctx-source-mapping
  (with-redefs [http/call (fn [_ _] (http/ok {}))]
    (let [endpoint (assoc base-endpoint
                          :output_mapping {:cid {:source "ctx" :key "customerId"}})
          {:keys [data]} (run-sync!
                           (executor/run-endpoint
                             endpoint {} {}
                             {:enrichers [(fn [_] {:customerId "c123"})]}))]
      (is (= "c123" (:cid data))))))
