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

(defn- rv
  "Convenience: run-validation with an empty validators registry."
  ([endpoint args ctx]              (validator/run-validation endpoint args ctx {}))
  ([endpoint args ctx validators]   (validator/run-validation endpoint args ctx validators)))

;; ---------------------------------------------------------------------------
;; built-in — pattern
;; ---------------------------------------------------------------------------

(deftest test-pattern-valid-passes
  (is (nil? (rv (assoc-in base-endpoint [:args :id :validate] {:pattern "^[a-z]+$"})
                {:id "abc"} {}))))

(deftest test-pattern-invalid-returns-error
  (let [errors (rv (assoc-in base-endpoint [:args :id :validate]
                             {:pattern "^[a-z]+$" :message "letters only"})
                   {:id "ABC123"} {})]
    (is (= 1 (count errors)))
    (is (= "letters only" (:message (first errors))))))

(deftest test-pattern-default-message-used-when-none-given
  (let [errors (rv (assoc-in base-endpoint [:args :id :validate] {:pattern "^[a-z]+$"})
                   {:id "123"} {})]
    (is (= 1 (count errors)))
    (is (string? (:message (first errors))))))

(deftest test-nil-value-skipped-by-pattern
  (is (nil? (rv (assoc-in base-endpoint [:args :id :validate] {:pattern "^[a-z]+$"})
                {:id nil} {}))))

;; ---------------------------------------------------------------------------
;; built-in — min / max
;; ---------------------------------------------------------------------------

(deftest test-min-valid-passes
  (is (nil? (rv (assoc-in base-endpoint [:args :amount :validate] {:min 0})
                {:amount 5} {}))))

(deftest test-min-invalid-returns-error
  (let [errors (rv (assoc-in base-endpoint [:args :amount :validate]
                             {:min 0 :message "must be non-negative"})
                   {:amount -1} {})]
    (is (= 1 (count errors)))
    (is (= "must be non-negative" (:message (first errors))))))

(deftest test-max-invalid-returns-error
  (let [errors (rv (assoc-in base-endpoint [:args :amount :validate]
                             {:max 100 :message "too large"})
                   {:amount 200} {})]
    (is (= 1 (count errors)))
    (is (= "too large" (:message (first errors))))))

(deftest test-min-and-max-both-pass
  (is (nil? (rv (assoc-in base-endpoint [:args :amount :validate] {:min 0 :max 100})
                {:amount 50} {}))))

(deftest test-nil-value-skipped-by-min-max
  (is (nil? (rv (assoc-in base-endpoint [:args :amount :validate] {:min 0 :max 100})
                {:amount nil} {}))))

;; ---------------------------------------------------------------------------
;; multiple args — each validated independently
;; ---------------------------------------------------------------------------

(deftest test-multiple-args-all-valid
  (let [endpoint (-> base-endpoint
                     (assoc-in [:args :userId :validate] {:pattern "^u\\d+$"})
                     (assoc-in [:args :amount :validate] {:min 0 :max 1000}))]
    (is (nil? (rv endpoint {:userId "u42" :amount 100} {})))))

(deftest test-multiple-args-both-invalid-returns-two-errors
  (let [endpoint (-> base-endpoint
                     (assoc-in [:args :userId :validate]
                               {:pattern "^u\\d+$" :message "bad userId"})
                     (assoc-in [:args :amount :validate]
                               {:min 0 :message "bad amount"}))]
    (is (= 2 (count (rv endpoint {:userId "BAD" :amount -5} {}))))))

;; ---------------------------------------------------------------------------
;; no validation declared — passes through
;; ---------------------------------------------------------------------------

(deftest test-no-validation-rules-returns-nil
  (is (nil? (rv base-endpoint {:userId "anything"} {}))))

;; ---------------------------------------------------------------------------
;; custom validator — provided inline via `validators` argument
;; ---------------------------------------------------------------------------

(deftest test-custom-validator-called-and-passes
  (is (nil? (rv (assoc base-endpoint :validator {:key "test-pass"})
                {} {}
                {"test-pass" (fn [_ _] nil)}))))

(deftest test-custom-validator-returns-errors
  (let [errors (rv (assoc base-endpoint :validator {:key "test-fail"})
                   {} {}
                   {"test-fail" (fn [_ _] [{:message "cross-field rule failed"}])})]
    (is (= 1 (count errors)))
    (is (= "cross-field rule failed" (:message (first errors))))))

(deftest test-custom-validator-receives-args-and-ctx
  (let [received (atom nil)]
    (rv (assoc base-endpoint :validator {:key "test-capture"})
        {:userId "u1"} {:authorization "Bearer tok"}
        {"test-capture" (fn [args ctx] (reset! received {:args args :ctx ctx}) nil)})
    (is (= {:userId "u1"} (:args @received)))
    (is (= {:authorization "Bearer tok"} (:ctx @received)))))

(deftest test-custom-validator-unknown-key-throws
  (is (thrown? clojure.lang.ExceptionInfo
               (rv (assoc base-endpoint :validator {:key "not-registered-xyz"})
                   {} {}))))

(deftest test-custom-validator-ns-fn-form
  (rv (assoc base-endpoint :validator {:ns "bff.validator-test" :fn "passing-validator"})
      {} {}))

(defn passing-validator [_ _] nil)

;; ---------------------------------------------------------------------------
;; protocol implementation
;; ---------------------------------------------------------------------------

(deftest test-custom-validator-protocol-implementation
  (let [impl (reify validator/BffValidator
               (validate [_ _ _] [{:message "from protocol"}]))
        errors (rv (assoc base-endpoint :validator {:key "test-protocol-validator"})
                   {} {}
                   {"test-protocol-validator" impl})]
    (is (= 1 (count errors)))
    (is (= "from protocol" (:message (first errors))))))

;; ---------------------------------------------------------------------------
;; Java interface — io.github.rthadani.bff.BffValidator
;; ---------------------------------------------------------------------------

(deftest test-java-interface-validator-args-passed-as-string-keys
  (testing "Java implementations receive String-keyed maps, not Clojure keywords"
    (let [seen (atom nil)
          impl (reify io.github.rthadani.bff.BffValidator
                 (validate [_ args _ctx] (reset! seen args) nil))]
      (rv (assoc base-endpoint :validator {:key "test-java-arg-keys"})
          {:userId "u42" :amount 100} {}
          {"test-java-arg-keys" impl})
      (is (instance? java.util.Map @seen))
      (is (= "u42" (.get ^java.util.Map @seen "userId")))
      (is (= 100   (.get ^java.util.Map @seen "amount"))))))

(deftest test-java-interface-validator-errors-keywordized
  (testing "String-keyed error maps returned from Java are converted back to keyword-keyed"
    (let [impl (reify io.github.rthadani.bff.BffValidator
                 (validate [_ _ _]
                   (java.util.List/of
                     (doto (java.util.HashMap.)
                       (.put "message" "java rejected")))))
          errors (rv (assoc base-endpoint :validator {:key "test-java-error-keys"})
                     {} {}
                     {"test-java-error-keys" impl})]
      (is (= 1 (count errors)))
      (is (= "java rejected" (:message (first errors)))))))

(deftest test-java-interface-validator-empty-list-passes
  (let [impl (reify io.github.rthadani.bff.BffValidator
               (validate [_ _ _] (java.util.List/of)))]
    (is (nil? (rv (assoc base-endpoint :validator {:key "test-java-empty"})
                  {} {}
                  {"test-java-empty" impl})))))

;; ---------------------------------------------------------------------------
;; builtin + custom combined
;; ---------------------------------------------------------------------------

(deftest test-builtin-and-custom-errors-combined
  (let [endpoint (-> base-endpoint
                     (assoc-in [:args :id :validate]
                               {:pattern "^[a-z]+$" :message "pattern failed"})
                     (assoc :validator {:key "test-extra-error"}))
        errors   (rv endpoint {:id "123"} {}
                     {"test-extra-error" (fn [_ _] [{:message "custom rule failed"}])})]
    (is (= 2 (count errors)))
    (is (some #(= "pattern failed" (:message %)) errors))
    (is (some #(= "custom rule failed" (:message %)) errors))))

;; ---------------------------------------------------------------------------
;; integration — validation short-circuits run-endpoint
;; ---------------------------------------------------------------------------

(deftest test-validation-failure-short-circuits-backend-chain
  (let [called (atom false)]
    (with-redefs [http/call (fn [_ _] (reset! called true) (http/ok {}))]
      (let [endpoint (assoc-in base-endpoint [:args :id :validate]
                               {:pattern "^[a-z]+$" :message "letters only"})
            {:keys [data errors]} (run-sync!
                                    (executor/run-endpoint endpoint {:id "123"} {} {}))]
        (is (false? @called) "HTTP must not be called when validation fails")
        (is (nil? data))
        (is (= 1 (count errors)))
        (is (= "letters only" (:message (first errors))))))))

(deftest test-validation-success-proceeds-to-chain
  (let [called (atom false)]
    (with-redefs [http/call (fn [_ _] (reset! called true) (http/ok {}))]
      (let [endpoint (assoc-in base-endpoint [:args :id :validate]
                               {:pattern "^[a-z]+$"})
            {:keys [errors]} (run-sync!
                               (executor/run-endpoint endpoint {:id "abc"} {} {}))]
        (is (true? @called) "HTTP must be called when validation passes")
        (is (empty? errors))))))
