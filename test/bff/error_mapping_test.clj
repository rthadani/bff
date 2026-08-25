(ns bff.error-mapping-test
  "Tests for spec-level errors mapping + resolver-returned :code surfaced via
   extensions.code."
  (:require [clojure.test :refer [deftest is testing]]
            [bff.executor :as executor]
            [bff.http-client :as http]
            [bff.schema-builder :as sb]
            [com.walmartlabs.lacinia :as lacinia]
            [missionary.core :as m]))

(defn- run-sync! [task]
  (let [p (java.util.concurrent.CompletableFuture.)]
    (task #(.complete p %) #(.completeExceptionally p %))
    (try (.get p)
         (catch java.util.concurrent.ExecutionException e (throw (.getCause e))))))

(def ^:private base-step
  {:id "s" :url "http://api" :method "GET" :deps []})

(def ^:private base-endpoint
  {:backend_chain  [base-step]
   :output_mapping {}})

(defn- run [endpoint]
  (run-sync! (executor/run-endpoint endpoint {} {} {})))

;; ---------------------------------------------------------------------------
;; Step-level errors mapping
;; ---------------------------------------------------------------------------

(deftest test-step-errors-mapping-by-http-status
  (let [step (assoc base-step :errors {400 "DUPLICATE_CUSTOMER"})]
    (with-redefs [http/call (fn [_ _] (http/err :bad-request "400" {} 400))]
      (let [{:keys [errors]} (run (assoc base-endpoint :backend_chain [step]))]
        (is (= "DUPLICATE_CUSTOMER" (get-in (first errors) [:extensions :code])))))))

(deftest test-step-errors-mapping-by-semantic-code
  (let [step (assoc base-step :errors {:unauthorized "TOKEN_EXPIRED"})]
    (with-redefs [http/call (fn [_ _] (http/err :unauthorized "401" {} 401))]
      (let [{:keys [errors]} (run (assoc base-endpoint :backend_chain [step]))]
        (is (= "TOKEN_EXPIRED" (get-in (first errors) [:extensions :code])))))))

(deftest test-step-errors-mapping-by-string-code
  (testing "YAML-loaded specs may present keys as strings"
    (let [step (assoc base-step :errors {"unauthorized" "TOKEN_EXPIRED"})]
      (with-redefs [http/call (fn [_ _] (http/err :unauthorized "401" {} 401))]
        (let [{:keys [errors]} (run (assoc base-endpoint :backend_chain [step]))]
          (is (= "TOKEN_EXPIRED" (get-in (first errors) [:extensions :code]))))))))

(deftest test-step-errors-mapping-http-status-takes-priority
  (let [step (assoc base-step :errors {401 "BY_STATUS"
                                       :unauthorized "BY_CODE"})]
    (with-redefs [http/call (fn [_ _] (http/err :unauthorized "401" {} 401))]
      (let [{:keys [errors]} (run (assoc base-endpoint :backend_chain [step]))]
        (is (= "BY_STATUS" (get-in (first errors) [:extensions :code])))))))

(deftest test-no-errors-mapping-preserves-semantic-code
  (with-redefs [http/call (fn [_ _] (http/err :unauthorized "401" {} 401))]
    (let [{:keys [errors]} (run base-endpoint)]
      (is (= :unauthorized (get-in (first errors) [:extensions :code]))))))

(deftest test-error-mapping-runs-after-retry
  (testing "retry logic uses the semantic code; mapping is applied to the final result only"
    (let [calls (atom 0)
          step  (-> base-step
                    (assoc :retry {:max 1 :on_code [:unauthorized]}
                           :errors {:unauthorized "TOKEN_EXPIRED"}))]
      (with-redefs [http/call (fn [_ _]
                                (swap! calls inc)
                                (http/err :unauthorized "401" {} 401))]
        (let [{:keys [errors]} (run (assoc base-endpoint :backend_chain [step]))]
          (is (= 2 @calls) "retry happened because on_code matched the semantic code")
          (is (= "TOKEN_EXPIRED"
                 (get-in (first errors) [:extensions :code]))
              "final surfaced code is the mapped domain code"))))))

;; ---------------------------------------------------------------------------
;; Resolver-returned :code lifted into extensions
;; ---------------------------------------------------------------------------

(def ^:private ping-spec
  {:endpoints
   [{:name "ping"
     :type "query"
     :args {}
     :output_type   {:name "PingResult" :fields {:message "String!"}}
     :backend_chain []
     :output_mapping {}}]})

(deftest test-resolver-code-lifted-into-extensions
  (let [schema (sb/build-schema ping-spec)]
    (with-redefs [executor/run-endpoint
                  (fn [_ _ _ _]
                    (m/sp {:data nil
                           :errors [{:message "MAC in use" :code "MAC_ALREADY_MAPPED"}]}))]
      (let [result (lacinia/execute schema "{ ping { message } }" {} {})]
        (is (= "MAC in use"
               (-> result :errors first :message)))
        (is (= "MAC_ALREADY_MAPPED"
               (-> result :errors first :extensions :code)))))))

(deftest test-resolver-explicit-extensions-preserved
  (let [schema (sb/build-schema ping-spec)]
    (with-redefs [executor/run-endpoint
                  (fn [_ _ _ _]
                    (m/sp {:data nil
                           :errors [{:message "boom"
                                     :extensions {:code "X" :retryable true}}]}))]
      (let [result (lacinia/execute schema "{ ping { message } }" {} {})]
        (is (= "X"   (-> result :errors first :extensions :code)))
        (is (true?   (-> result :errors first :extensions :retryable)))))))

(deftest test-resolver-top-level-code-does-not-overwrite-explicit-extensions
  (let [schema (sb/build-schema ping-spec)]
    (with-redefs [executor/run-endpoint
                  (fn [_ _ _ _]
                    (m/sp {:data nil
                           :errors [{:message "boom"
                                     :code       "TOP_LEVEL"
                                     :extensions {:code "EXPLICIT"}}]}))]
      (let [result (lacinia/execute schema "{ ping { message } }" {} {})]
        (is (= "EXPLICIT" (-> result :errors first :extensions :code)))))))

(deftest test-resolver-error-without-code-still-surfaces-message
  (let [schema (sb/build-schema ping-spec)]
    (with-redefs [executor/run-endpoint
                  (fn [_ _ _ _]
                    (m/sp {:data nil :errors [{:message "just a message"}]}))]
      (let [result (lacinia/execute schema "{ ping { message } }" {} {})]
        (is (= "just a message" (-> result :errors first :message)))))))
