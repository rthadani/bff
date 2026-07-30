(ns bff.enricher-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [bff.enricher :as enricher]
            [bff.executor :as executor]
            [bff.validator :as validator]
            [bff.http-client :as http]))

(defn- run-sync! [task]
  (let [p (java.util.concurrent.CompletableFuture.)]
    (task #(.complete p %) #(.completeExceptionally p %))
    (try
      (.get p)
      (catch java.util.concurrent.ExecutionException e
        (throw (.getCause e))))))

(use-fixtures :each
  (fn [f]
    (enricher/reset-enrichers!)
    (f)
    (enricher/reset-enrichers!)))

(def ^:private base-step
  {:id "s" :url "http://api" :method "GET" :deps []})

(def ^:private base-endpoint
  {:backend_chain [base-step] :output_mapping {}})

;; ---------------------------------------------------------------------------
;; enrich-ctx: pure function
;; ---------------------------------------------------------------------------

(deftest test-no-enrichers-ctx-unchanged
  (is (= {:a 1} (enricher/enrich-ctx {:a 1}))))

(deftest test-nil-ctx-becomes-empty-map
  (is (= {} (enricher/enrich-ctx nil))))

(deftest test-single-enricher-adds-keys
  (enricher/register-enricher! (fn [_] {:customerId "c1"}))
  (is (= {:authorization "tok" :customerId "c1"}
         (enricher/enrich-ctx {:authorization "tok"}))))

(deftest test-enricher-returning-nil-leaves-ctx-alone
  (enricher/register-enricher! (fn [_] nil))
  (is (= {:a 1} (enricher/enrich-ctx {:a 1}))))

(deftest test-enricher-returning-non-map-ignored
  (enricher/register-enricher! (fn [_] "not-a-map"))
  (is (= {:a 1} (enricher/enrich-ctx {:a 1}))))

(deftest test-multiple-enrichers-run-in-order
  (enricher/register-enricher! (fn [_]    {:step-1 true}))
  (enricher/register-enricher! (fn [ctx]  {:step-2 (:step-1 ctx)}))
  (is (= {:step-1 true :step-2 true}
         (enricher/enrich-ctx {}))))

(deftest test-later-enricher-overwrites-earlier-key
  (enricher/register-enricher! (fn [_] {:tag "first"}))
  (enricher/register-enricher! (fn [_] {:tag "second"}))
  (is (= "second" (:tag (enricher/enrich-ctx {})))))

;; ---------------------------------------------------------------------------
;; Java interface — io.github.rthadani.bff.BffContextEnricher
;; ---------------------------------------------------------------------------

(deftest test-java-interface-enricher-registered
  (let [impl (reify io.github.rthadani.bff.BffContextEnricher
               (enrich [_ ctx]
                 (doto (java.util.HashMap.)
                   (.put "customerId" (str "cust-for-" (.get ^java.util.Map ctx "userId"))))))]
    (enricher/register-enricher! impl)
    (is (= "cust-for-u42"
           (:customerId (enricher/enrich-ctx {:userId "u42"}))))))

(deftest test-java-interface-null-return-preserves-ctx
  (let [impl (reify io.github.rthadani.bff.BffContextEnricher
               (enrich [_ _ctx] nil))]
    (enricher/register-enricher! impl)
    (is (= {:a 1} (enricher/enrich-ctx {:a 1})))))

(deftest test-bff-facade-register-context-enricher
  (let [impl (reify io.github.rthadani.bff.BffContextEnricher
               (enrich [_ _ctx]
                 (doto (java.util.HashMap.) (.put "viaFacade" true))))]
    (io.github.rthadani.bff.Bff/registerContextEnricher impl)
    (is (true? (:viaFacade (enricher/enrich-ctx {}))))))

;; ---------------------------------------------------------------------------
;; Integration — enriched values visible to validators and backend steps
;; ---------------------------------------------------------------------------

(deftest test-enricher-values-visible-to-validator
  (let [seen (atom nil)]
    (enricher/register-enricher! (fn [_] {:customerId "c99"}))
    (validator/register-validator! "capture-ctx"
      (fn [_args ctx] (reset! seen ctx) nil))
    (with-redefs [http/call (fn [_] (http/ok {}))]
      (run-sync! (executor/run-endpoint
                   (assoc base-endpoint :validator {:key "capture-ctx"})
                   {} {:authorization "tok"})))
    (is (= "c99"  (:customerId @seen)))
    (is (= "tok"  (:authorization @seen)))))

(deftest test-enricher-values-forwarded-to-backend-step-headers
  (enricher/register-enricher! (fn [_] {:x-customer-id "cust-42"}))
  (let [seen-headers (atom nil)]
    (with-redefs [http/call (fn [{:keys [headers]}]
                              (reset! seen-headers headers)
                              (http/ok {}))]
      (run-sync! (executor/run-endpoint base-endpoint {} {:authorization "tok"})))
    (is (= "cust-42" (get @seen-headers "x-customer-id")))
    (is (= "tok"     (get @seen-headers "authorization")))))

(deftest test-enricher-values-visible-via-ctx-source-mapping
  (enricher/register-enricher! (fn [_] {:customerId "c123"}))
  (with-redefs [http/call (fn [_] (http/ok {}))]
    (let [endpoint (assoc base-endpoint
                          :output_mapping {:cid {:source "ctx" :key "customerId"}})
          {:keys [data]} (run-sync! (executor/run-endpoint endpoint {} {}))]
      (is (= "c123" (:cid data))))))
