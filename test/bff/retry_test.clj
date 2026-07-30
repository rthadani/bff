(ns bff.retry-test
  (:require [clojure.test :refer [deftest is testing]]
            [bff.retry :as retry]
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
  {:backend_chain  [base-step]
   :output_mapping {}})

;; ---------------------------------------------------------------------------
;; should-retry?
;; ---------------------------------------------------------------------------

(deftest test-should-retry-nil-config-never-retries
  (is (false? (retry/should-retry? nil (http/err :unauthorized "401") 0))))

(deftest test-should-retry-ok-result-never-retries
  (is (false? (retry/should-retry? {:max 2 :on_code [:unauthorized]}
                                   (http/ok {}) 0))))

(deftest test-should-retry-code-not-in-allowlist
  (is (false? (retry/should-retry? {:max 2 :on_code [:unauthorized]}
                                   (http/err :not-found "404") 0))))

(deftest test-should-retry-code-in-allowlist
  (is (true? (retry/should-retry? {:max 2 :on_code [:unauthorized]}
                                  (http/err :unauthorized "401") 0))))

(deftest test-should-retry-budget-exhausted
  (is (false? (retry/should-retry? {:max 2 :on_code [:unauthorized]}
                                   (http/err :unauthorized "401") 2))))

(deftest test-should-retry-on-code-accepts-strings
  (testing "on_code may be strings (from YAML) or keywords"
    (is (true? (retry/should-retry? {:max 1 :on_code ["unauthorized"]}
                                    (http/err :unauthorized "401") 0)))))

;; ---------------------------------------------------------------------------
;; End-to-end via execute-graph
;; ---------------------------------------------------------------------------

(deftest test-step-with-no-retry-config-called-once
  (let [calls (atom 0)]
    (with-redefs [http/call (fn [_] (swap! calls inc) (http/err :timeout "t"))]
      (run-sync! (executor/execute-graph [base-step] {} {}))
      (is (= 1 @calls)))))

(deftest test-step-retries-on-matching-code-and-recovers
  (let [calls    (atom 0)
        step     (assoc base-step :retry {:max 2 :on_code [:unauthorized]})]
    (with-redefs [http/call (fn [_]
                              (swap! calls inc)
                              (if (= 1 @calls)
                                (http/err :unauthorized "401")
                                (http/ok {:recovered true})))]
      (let [ctx (run-sync! (executor/execute-graph [step] {} {}))]
        (is (= 2 @calls))
        (is (= :ok (get-in ctx [:s :status])))))))

(deftest test-step-stops-after-max-retries
  (let [calls (atom 0)
        step  (assoc base-step :retry {:max 2 :on_code [:unauthorized]})]
    (with-redefs [http/call (fn [_]
                              (swap! calls inc)
                              (http/err :unauthorized "401"))]
      (let [ctx (run-sync! (executor/execute-graph [step] {} {}))]
        (is (= 3 @calls) "1 initial + 2 retries")
        (is (= :error (get-in ctx [:s :status])))))))

(deftest test-step-does-not-retry-on-different-code
  (let [calls (atom 0)
        step  (assoc base-step :retry {:max 2 :on_code [:unauthorized]})]
    (with-redefs [http/call (fn [_]
                              (swap! calls inc)
                              (http/err :not-found "404"))]
      (run-sync! (executor/execute-graph [step] {} {}))
      (is (= 1 @calls)))))

;; ---------------------------------------------------------------------------
;; before-retry hook
;; ---------------------------------------------------------------------------

(deftest test-before-retry-hook-receives-failure-context
  (let [seen (atom nil)
        step (assoc base-step
                    :retry {:max 1 :on_code [:unauthorized]
                            :before_retry {:key "capture-hook"}})]
    (retry/register-retry-hook! "capture-hook"
      (fn [ctx] (reset! seen ctx) nil))
    (with-redefs [http/call (fn [_] (http/err :unauthorized "401"))]
      (run-sync! (executor/execute-graph [step] {:userId "u1"} {:authorization "old"}))
      (is (= :s      (:step-id     @seen)))
      (is (= 1       (:attempt     @seen)))
      (is (= {:userId "u1"} (:args @seen)))
      (is (= "old"   (get-in @seen [:request-ctx :authorization])))
      (is (= :unauthorized (get-in @seen [:error :code]))))))

(deftest test-before-retry-hook-can-rewrite-request-ctx
  (let [seen-headers (atom [])
        step (assoc base-step
                    :retry {:max 1 :on_code [:unauthorized]
                            :before_retry {:key "refresh-hook"}})]
    (retry/register-retry-hook! "refresh-hook"
      (fn [ctx] (assoc (:request-ctx ctx) :authorization "Bearer fresh")))
    (with-redefs [http/call (fn [{:keys [headers]}]
                              (swap! seen-headers conj (get headers "authorization"))
                              (http/err :unauthorized "401"))]
      (run-sync! (executor/execute-graph [step] {} {:authorization "Bearer stale"}))
      (is (= ["Bearer stale" "Bearer fresh"] @seen-headers)))))

(deftest test-before-retry-hook-returning-nil-reuses-current-ctx
  (let [seen-headers (atom [])
        step (assoc base-step
                    :retry {:max 1 :on_code [:unauthorized]
                            :before_retry {:key "noop-hook"}})]
    (retry/register-retry-hook! "noop-hook" (fn [_] nil))
    (with-redefs [http/call (fn [{:keys [headers]}]
                              (swap! seen-headers conj (get headers "authorization"))
                              (http/err :unauthorized "401"))]
      (run-sync! (executor/execute-graph [step] {} {:authorization "Bearer x"}))
      (is (= ["Bearer x" "Bearer x"] @seen-headers)))))

(deftest test-before-retry-hook-unknown-key-throws
  (let [step (assoc base-step
                    :retry {:max 1 :on_code [:unauthorized]
                            :before_retry {:key "does-not-exist"}})]
    (with-redefs [http/call (fn [_] (http/err :unauthorized "401"))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (run-sync! (executor/execute-graph [step] {} {})))))))

(deftest test-before-retry-hook-ns-fn-form
  (let [step (assoc base-step
                    :retry {:max 1 :on_code [:unauthorized]
                            :before_retry {:ns "bff.retry-test"
                                           :fn "sample-hook"}})]
    (with-redefs [http/call (fn [_] (http/err :unauthorized "401"))]
      (run-sync! (executor/execute-graph [step] {} {:authorization "old"})))))

(defn sample-hook [_ctx] nil)