(ns bff.validator-test
  (:require [clojure.test :refer [deftest is testing]]
            [bff.validator :as validator]
            [bff.executor :as executor]
            [bff.http-client :as http]))

(defn- run-sync! [task]
  (let [p (java.util.concurrent.CompletableFuture.)]
    (task #(.complete p %) #(.completeExceptionally p %))
    (try
      (.get p)
      (catch java.util.concurrent.ExecutionException e
        (throw (.getCause e))))))

(def ^:private base-endpoint
  {:backend_chain [{:id "s" :url "http://test.example/api" :method "GET" :deps []}]
   :output_mapping {}})

;; ---------------------------------------------------------------------------
;; built-in — pattern
;; ---------------------------------------------------------------------------

(deftest test-pattern-valid-passes
  (is (nil? (validator/run-validation
              (assoc-in base-endpoint [:args :id :validate] {:pattern "^[a-z]+$"})
              {:id "abc"}
              {}))))

(deftest test-pattern-invalid-returns-error
  (let [errors (validator/run-validation
                 (assoc-in base-endpoint [:args :id :validate]
                           {:pattern "^[a-z]+$" :message "letters only"})
                 {:id "ABC123"}
                 {})]
    (is (= 1 (count errors)))
    (is (= "letters only" (:message (first errors))))))

(deftest test-pattern-default-message-used-when-none-given
  (let [errors (validator/run-validation
                 (assoc-in base-endpoint [:args :id :validate] {:pattern "^[a-z]+$"})
                 {:id "123"}
                 {})]
    (is (= 1 (count errors)))
    (is (string? (:message (first errors))))))

(deftest test-nil-value-skipped-by-pattern
  (is (nil? (validator/run-validation
              (assoc-in base-endpoint [:args :id :validate] {:pattern "^[a-z]+$"})
              {:id nil}
              {}))))

;; ---------------------------------------------------------------------------
;; built-in — min / max
;; ---------------------------------------------------------------------------

(deftest test-min-valid-passes
  (is (nil? (validator/run-validation
              (assoc-in base-endpoint [:args :amount :validate] {:min 0})
              {:amount 5}
              {}))))

(deftest test-min-invalid-returns-error
  (let [errors (validator/run-validation
                 (assoc-in base-endpoint [:args :amount :validate]
                           {:min 0 :message "must be non-negative"})
                 {:amount -1}
                 {})]
    (is (= 1 (count errors)))
    (is (= "must be non-negative" (:message (first errors))))))

(deftest test-max-invalid-returns-error
  (let [errors (validator/run-validation
                 (assoc-in base-endpoint [:args :amount :validate]
                           {:max 100 :message "too large"})
                 {:amount 200}
                 {})]
    (is (= 1 (count errors)))
    (is (= "too large" (:message (first errors))))))

(deftest test-min-and-max-both-pass
  (is (nil? (validator/run-validation
              (assoc-in base-endpoint [:args :amount :validate] {:min 0 :max 100})
              {:amount 50}
              {}))))

(deftest test-nil-value-skipped-by-min-max
  (is (nil? (validator/run-validation
              (assoc-in base-endpoint [:args :amount :validate] {:min 0 :max 100})
              {:amount nil}
              {}))))

;; ---------------------------------------------------------------------------
;; multiple args — each validated independently
;; ---------------------------------------------------------------------------

(deftest test-multiple-args-all-valid
  (let [endpoint (-> base-endpoint
                     (assoc-in [:args :userId :validate] {:pattern "^u\\d+$"})
                     (assoc-in [:args :amount :validate] {:min 0 :max 1000}))]
    (is (nil? (validator/run-validation endpoint {:userId "u42" :amount 100} {})))))

(deftest test-multiple-args-both-invalid-returns-two-errors
  (let [endpoint (-> base-endpoint
                     (assoc-in [:args :userId :validate]
                               {:pattern "^u\\d+$" :message "bad userId"})
                     (assoc-in [:args :amount :validate]
                               {:min 0 :message "bad amount"}))]
    (is (= 2 (count (validator/run-validation
                      endpoint {:userId "BAD" :amount -5} {}))))))

;; ---------------------------------------------------------------------------
;; no validation declared — passes through
;; ---------------------------------------------------------------------------

(deftest test-no-validation-rules-returns-nil
  (is (nil? (validator/run-validation base-endpoint {:userId "anything"} {}))))

;; ---------------------------------------------------------------------------
;; custom validator
;; ---------------------------------------------------------------------------

(deftest test-custom-validator-called-and-passes
  (validator/register-validator! "test-pass" (fn [_ _] nil))
  (is (nil? (validator/run-validation
              (assoc base-endpoint :validator {:key "test-pass"})
              {} {}))))

(deftest test-custom-validator-returns-errors
  (validator/register-validator! "test-fail"
    (fn [_ _] [{:message "cross-field rule failed"}]))
  (let [errors (validator/run-validation
                 (assoc base-endpoint :validator {:key "test-fail"})
                 {} {})]
    (is (= 1 (count errors)))
    (is (= "cross-field rule failed" (:message (first errors))))))

(deftest test-custom-validator-receives-args-and-ctx
  (let [received (atom nil)]
    (validator/register-validator! "test-capture"
      (fn [args ctx] (reset! received {:args args :ctx ctx}) nil))
    (validator/run-validation
      (assoc base-endpoint :validator {:key "test-capture"})
      {:userId "u1"} {:authorization "Bearer tok"})
    (is (= {:userId "u1"} (:args @received)))
    (is (= {:authorization "Bearer tok"} (:ctx @received)))))

(deftest test-custom-validator-unknown-key-throws
  (is (thrown? clojure.lang.ExceptionInfo
               (validator/run-validation
                 (assoc base-endpoint :validator {:key "not-registered-xyz"})
                 {} {}))))

(deftest test-custom-validator-ns-fn-form
  (validator/run-validation
    (assoc base-endpoint :validator {:ns "bff.validator-test" :fn "passing-validator"})
    {} {}))

(defn passing-validator [_ _] nil)

;; ---------------------------------------------------------------------------
;; protocol implementation
;; ---------------------------------------------------------------------------

(deftest test-custom-validator-protocol-implementation
  (let [impl (reify validator/BffValidator
               (validate [_ _ _] [{:message "from protocol"}]))]
    (validator/register-validator! "test-protocol-validator" impl)
    (let [errors (validator/run-validation
                   (assoc base-endpoint :validator {:key "test-protocol-validator"})
                   {} {})]
      (is (= 1 (count errors)))
      (is (= "from protocol" (:message (first errors)))))))

;; ---------------------------------------------------------------------------
;; builtin + custom combined
;; ---------------------------------------------------------------------------

(deftest test-builtin-and-custom-errors-combined
  (validator/register-validator! "test-extra-error"
    (fn [_ _] [{:message "custom rule failed"}]))
  (let [endpoint (-> base-endpoint
                     (assoc-in [:args :id :validate]
                               {:pattern "^[a-z]+$" :message "pattern failed"})
                     (assoc :validator {:key "test-extra-error"}))]
    (let [errors (validator/run-validation endpoint {:id "123"} {})]
      (is (= 2 (count errors)))
      (is (some #(= "pattern failed" (:message %)) errors))
      (is (some #(= "custom rule failed" (:message %)) errors)))))

;; ---------------------------------------------------------------------------
;; integration — validation short-circuits run-endpoint
;; ---------------------------------------------------------------------------

(deftest test-validation-failure-short-circuits-backend-chain
  (let [called (atom false)]
    (with-redefs [http/call (fn [_] (reset! called true) (http/ok {}))]
      (let [endpoint (assoc-in base-endpoint [:args :id :validate]
                               {:pattern "^[a-z]+$" :message "letters only"})
            {:keys [data errors]} (run-sync!
                                    (executor/run-endpoint endpoint {:id "123"} {}))]
        (is (false? @called) "HTTP must not be called when validation fails")
        (is (nil? data))
        (is (= 1 (count errors)))
        (is (= "letters only" (:message (first errors))))))))

(deftest test-validation-success-proceeds-to-chain
  (let [called (atom false)]
    (with-redefs [http/call (fn [_] (reset! called true) (http/ok {}))]
      (let [endpoint (assoc-in base-endpoint [:args :id :validate]
                               {:pattern "^[a-z]+$"})
            {:keys [errors]} (run-sync!
                               (executor/run-endpoint endpoint {:id "abc"} {}))]
        (is (true? @called) "HTTP must be called when validation passes")
        (is (empty? errors))))))
