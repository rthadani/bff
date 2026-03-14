(ns bff.executor-test
  (:require [clojure.test :refer [deftest is testing]]
            [bff.executor :as executor]
            [bff.http-client :as http]
            [bff.jq-engine :as jq]))

(defn- run-sync! [task]
  (let [p (java.util.concurrent.CompletableFuture.)]
    (task #(.complete p %) #(.completeExceptionally p %))
    (try
      (.get p)
      (catch java.util.concurrent.ExecutionException e
        (throw (.getCause e))))))

(def ^:private base-step
  {:url "http://test.example/api" :method "GET" :deps []})

(def ^:private base-endpoint
  {:backend_chain [(assoc base-step :id "s")]
   :output_mapping {}})

(defn test-transformer-fn [_ _ m] (assoc m :via-ns true))

;; ---------------------------------------------------------------------------
;; execute-graph
;; ---------------------------------------------------------------------------

(deftest test-execute-graph-single-step-result-in-ctx
  (with-redefs [http/call (fn [_] (http/ok {:id 1}))]
    (let [ctx (run-sync! (executor/execute-graph [(assoc base-step :id "a")] {} {}))]
      (is (= :ok (get-in ctx [:a :status])))
      (is (= {:id 1} (get-in ctx [:a :data]))))))

(deftest test-execute-graph-parallel-steps-both-in-ctx
  (with-redefs [http/call (fn [_] (http/ok {}))]
    (let [chain [(assoc base-step :id "a")
                 (assoc base-step :id "b")]
          ctx   (run-sync! (executor/execute-graph chain {} {}))]
      (is (contains? ctx :a))
      (is (contains? ctx :b)))))

(deftest test-execute-graph-sequential-steps-both-in-ctx
  (with-redefs [http/call (fn [_] (http/ok {}))]
    (let [chain [(assoc base-step :id "a")
                 (assoc base-step :id "b" :deps ["a"])]
          ctx   (run-sync! (executor/execute-graph chain {} {}))]
      (is (contains? ctx :a))
      (is (contains? ctx :b)))))

(deftest test-execute-graph-url-interpolation-from-args
  (let [captured (atom nil)]
    (with-redefs [http/call (fn [{:keys [url]}]
                              (reset! captured url)
                              (http/ok {}))]
      (let [chain [(assoc base-step :id "s" :url "http://api/{userId}")]]
        (run-sync! (executor/execute-graph chain {:userId "u99"} {}))
        (is (= "http://api/u99" @captured))))))

(deftest test-execute-graph-url-interpolation-from-chain-ctx
  (let [calls (atom [])]
    (with-redefs [http/call (fn [{:keys [url]}]
                              (swap! calls conj url)
                              (http/ok {:token "abc123"}))]
      (let [chain [(assoc base-step :id "fetch")
                   {:id "use" :url "http://api/{token}" :method "GET" :deps ["fetch"]}]]
        (run-sync! (executor/execute-graph chain {} {}))
        (is (= "http://api/abc123" (second @calls)))))))

(deftest test-execute-graph-critical-failure-throws
  (with-redefs [http/call (fn [_] (http/err :not-found "404"))]
    (let [chain [(assoc base-step :id "s" :critical true)]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (run-sync! (executor/execute-graph chain {} {})))))))

(deftest test-execute-graph-critical-failure-ex-data-has-step
  (with-redefs [http/call (fn [_] (http/err :not-found "404"))]
    (let [chain [(assoc base-step :id "s" :critical true)]]
      (try
        (run-sync! (executor/execute-graph chain {} {}))
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= "s" (:step (ex-data e)))))))))

(deftest test-execute-graph-non-critical-failure-captured-in-ctx
  (with-redefs [http/call (fn [_] (http/err :timeout "timeout"))]
    (let [chain [(assoc base-step :id "s")]
          ctx   (run-sync! (executor/execute-graph chain {} {}))]
      (is (= :error (get-in ctx [:s :status]))))))

(deftest test-execute-graph-mixed-ok-and-error
  (let [responses {:a (http/ok {:x 1}) :b (http/err :timeout "timeout")}]
    (with-redefs [http/call (fn [{:keys [step-id]}]
                              (get responses step-id (http/ok {})))]
      (let [chain [(assoc base-step :id "a")
                   (assoc base-step :id "b" :deps ["a"])]
            ctx   (run-sync! (executor/execute-graph chain {} {}))]
        (is (= :ok    (get-in ctx [:a :status])))
        (is (= :error (get-in ctx [:b :status])))))))

;; ---------------------------------------------------------------------------
;; run-endpoint — output mapping sources
;; ---------------------------------------------------------------------------

(deftest test-run-endpoint-empty-output-mapping-returns-empty-data
  (with-redefs [http/call (fn [_] (http/ok {:x 1}))]
    (let [{:keys [data errors]} (run-sync! (executor/run-endpoint base-endpoint {} {}))]
      (is (= {} data))
      (is (empty? errors)))))

(deftest test-run-endpoint-output-from-args
  (with-redefs [http/call (fn [_] (http/ok {}))]
    (let [endpoint (assoc base-endpoint :output_mapping {:echo {:source "args" :key "input"}})
          {:keys [data]} (run-sync! (executor/run-endpoint endpoint {:input "hello"} {}))]
      (is (= "hello" (:echo data))))))

(deftest test-run-endpoint-output-from-value-literal
  (with-redefs [http/call (fn [_] (http/ok {}))]
    (let [endpoint (assoc base-endpoint :output_mapping {:ver {:source "value" :value "2.0"}})
          {:keys [data]} (run-sync! (executor/run-endpoint endpoint {} {}))]
      (is (= "2.0" (:ver data))))))

(deftest test-run-endpoint-output-from-request-ctx
  (with-redefs [http/call (fn [_] (http/ok {}))]
    (let [endpoint (assoc base-endpoint :output_mapping {:rid {:source "ctx" :key "x-request-id"}})
          {:keys [data]} (run-sync! (executor/run-endpoint endpoint {} {:x-request-id "req-99"}))]
      (is (= "req-99" (:rid data))))))

(deftest test-run-endpoint-output-from-step-plain-key
  (with-redefs [http/call (fn [_] (http/ok {:user-id "u1"}))]
    (let [endpoint (assoc base-endpoint :output_mapping {:userId {:source "step" :step_id "s" :key "user-id"}})
          {:keys [data]} (run-sync! (executor/run-endpoint endpoint {} {}))]
      (is (= "u1" (:userId data))))))

(deftest test-run-endpoint-output-from-step-jq
  (with-redefs [http/call (fn [_] (http/ok {:profile {:name "Bob"}}))]
    (let [endpoint (assoc base-endpoint
                          :output_mapping {:name {:source "step" :step_id "s"
                                                  :jq ".profile.name"
                                                  :compiled-jq (jq/compile-query ".profile.name")}})
          {:keys [data]} (run-sync! (executor/run-endpoint endpoint {} {}))]
      (is (= "Bob" (:name data))))))

(deftest test-run-endpoint-unresolvable-step-field-is-nil
  (with-redefs [http/call (fn [_] (http/err :not-found "404"))]
    (let [endpoint (assoc base-endpoint :output_mapping {:id {:source "step" :step_id "s" :key "id"}})
          {:keys [data]} (run-sync! (executor/run-endpoint endpoint {} {}))]
      (is (nil? (:id data))))))

;; ---------------------------------------------------------------------------
;; run-endpoint — errors
;; ---------------------------------------------------------------------------

(deftest test-run-endpoint-step-error-in-errors
  (with-redefs [http/call (fn [_] (http/err :not-found "404"))]
    (let [{:keys [errors]} (run-sync! (executor/run-endpoint base-endpoint {} {}))]
      (is (= 1 (count errors)))
      (is (= :not-found (get-in (first errors) [:extensions :code]))))))

(deftest test-run-endpoint-error-step-name-in-extensions
  (with-redefs [http/call (fn [_] (http/err :timeout "timeout"))]
    (let [{:keys [errors]} (run-sync! (executor/run-endpoint base-endpoint {} {}))]
      (is (= "s" (get-in (first errors) [:extensions :step]))))))

(deftest test-run-endpoint-success-has-empty-errors
  (with-redefs [http/call (fn [_] (http/ok {:x 1}))]
    (let [{:keys [errors]} (run-sync! (executor/run-endpoint base-endpoint {} {}))]
      (is (empty? errors)))))

;; ---------------------------------------------------------------------------
;; register-transformer! / transformer dispatch
;; ---------------------------------------------------------------------------

(deftest test-register-transformer-key-is-called
  (executor/register-transformer! "test-add-flag" (fn [_ _ m] (assoc m :flag true)))
  (with-redefs [http/call (fn [_] (http/ok {}))]
    (let [endpoint (assoc base-endpoint :transformer {:key "test-add-flag"})
          {:keys [data]} (run-sync! (executor/run-endpoint endpoint {} {}))]
      (is (true? (:flag data))))))

(deftest test-register-transformer-receives-args-and-chain-ctx
  (let [received (atom nil)]
    (executor/register-transformer! "test-capture"
                                    (fn [args chain-ctx m]
                                      (reset! received {:args args :chain-ctx chain-ctx})
                                      m))
    (with-redefs [http/call (fn [_] (http/ok {}))]
      (run-sync! (executor/run-endpoint
                  (assoc base-endpoint :transformer {:key "test-capture"})
                  {:userId "u1"}
                  {}))
      (is (= {:userId "u1"} (:args @received)))
      (is (contains? (:chain-ctx @received) :s)))))

(deftest test-transformer-unknown-key-throws
  (with-redefs [http/call (fn [_] (http/ok {}))]
    (let [endpoint (assoc base-endpoint :transformer {:key "definitely-not-registered"})]
      (is (thrown? clojure.lang.ExceptionInfo
                   (run-sync! (executor/run-endpoint endpoint {} {})))))))

(deftest test-transformer-unknown-key-ex-data-has-key
  (with-redefs [http/call (fn [_] (http/ok {}))]
    (let [endpoint (assoc base-endpoint :transformer {:key "definitely-not-registered-2"})]
      (try
        (run-sync! (executor/run-endpoint endpoint {} {}))
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= "definitely-not-registered-2" (:key (ex-data e)))))))))

(deftest test-transformer-ns-fn-form-resolves-and-calls
  (with-redefs [http/call (fn [_] (http/ok {}))]
    (let [endpoint (assoc base-endpoint
                          :transformer {:ns "bff.executor-test" :fn "test-transformer-fn"})
          {:keys [data]} (run-sync! (executor/run-endpoint endpoint {} {}))]
      (is (true? (:via-ns data))))))

(deftest test-no-transformer-returns-mapped-output-unchanged
  (with-redefs [http/call (fn [_] (http/ok {}))]
    (let [endpoint (assoc base-endpoint :output_mapping {:v {:source "value" :value 42}})
          {:keys [data]} (run-sync! (executor/run-endpoint endpoint {} {}))]
      (is (= 42 (:v data))))))
