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

(defn- run [endpoint args ctx]
  (run-sync! (executor/run-endpoint endpoint args ctx {})))

(defn- run-with-exts [endpoint args ctx exts]
  (run-sync! (executor/run-endpoint endpoint args ctx exts)))

(defn- graph [chain args ctx]
  (run-sync! (executor/execute-graph chain args ctx {})))

(defn test-transformer-fn [_ _ m] (assoc m :via-ns true))

;; ---------------------------------------------------------------------------
;; execute-graph
;; ---------------------------------------------------------------------------

(deftest test-execute-graph-single-step-result-in-ctx
  (with-redefs [http/call (fn [_] (http/ok {:id 1}))]
    (let [ctx (graph [(assoc base-step :id "a")] {} {})]
      (is (= :ok (get-in ctx [:a :status])))
      (is (= {:id 1} (get-in ctx [:a :data]))))))

(deftest test-execute-graph-parallel-steps-both-in-ctx
  (with-redefs [http/call (fn [_] (http/ok {}))]
    (let [chain [(assoc base-step :id "a")
                 (assoc base-step :id "b")]
          ctx   (graph chain {} {})]
      (is (contains? ctx :a))
      (is (contains? ctx :b)))))

(deftest test-execute-graph-sequential-steps-both-in-ctx
  (with-redefs [http/call (fn [_] (http/ok {}))]
    (let [chain [(assoc base-step :id "a")
                 (assoc base-step :id "b" :deps ["a"])]
          ctx   (graph chain {} {})]
      (is (contains? ctx :a))
      (is (contains? ctx :b)))))

(deftest test-execute-graph-url-interpolation-from-args
  (let [captured (atom nil)]
    (with-redefs [http/call (fn [{:keys [url]}]
                              (reset! captured url)
                              (http/ok {}))]
      (graph [(assoc base-step :id "s" :url "http://api/{userId}")] {:userId "u99"} {})
      (is (= "http://api/u99" @captured)))))

(deftest test-execute-graph-url-interpolation-from-chain-ctx
  (let [calls (atom [])]
    (with-redefs [http/call (fn [{:keys [url]}]
                              (swap! calls conj url)
                              (http/ok {:token "abc123"}))]
      (graph [(assoc base-step :id "fetch")
              {:id "use" :url "http://api/{token}" :method "GET" :deps ["fetch"]}]
             {} {})
      (is (= "http://api/abc123" (second @calls))))))

(deftest test-execute-graph-critical-failure-throws
  (with-redefs [http/call (fn [_] (http/err :not-found "404"))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (graph [(assoc base-step :id "s" :critical true)] {} {})))))

(deftest test-execute-graph-critical-failure-ex-data-has-step
  (with-redefs [http/call (fn [_] (http/err :not-found "404"))]
    (try
      (graph [(assoc base-step :id "s" :critical true)] {} {})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= "s" (:step (ex-data e))))))))

(deftest test-execute-graph-non-critical-failure-captured-in-ctx
  (with-redefs [http/call (fn [_] (http/err :timeout "timeout"))]
    (is (= :error (get-in (graph [(assoc base-step :id "s")] {} {})
                          [:s :status])))))

(deftest test-execute-graph-mixed-ok-and-error
  (let [responses {:a (http/ok {:x 1}) :b (http/err :timeout "timeout")}]
    (with-redefs [http/call (fn [{:keys [step-id]}]
                              (get responses step-id (http/ok {})))]
      (let [ctx (graph [(assoc base-step :id "a")
                        (assoc base-step :id "b" :deps ["a"])]
                       {} {})]
        (is (= :ok    (get-in ctx [:a :status])))
        (is (= :error (get-in ctx [:b :status])))))))

;; ---------------------------------------------------------------------------
;; run-endpoint — output mapping sources
;; ---------------------------------------------------------------------------

(deftest test-run-endpoint-empty-output-mapping-returns-empty-data
  (with-redefs [http/call (fn [_] (http/ok {:x 1}))]
    (let [{:keys [data errors]} (run base-endpoint {} {})]
      (is (= {} data))
      (is (empty? errors)))))

(deftest test-run-endpoint-output-from-args
  (with-redefs [http/call (fn [_] (http/ok {}))]
    (let [endpoint (assoc base-endpoint :output_mapping {:echo {:source "args" :key "input"}})
          {:keys [data]} (run endpoint {:input "hello"} {})]
      (is (= "hello" (:echo data))))))

(deftest test-run-endpoint-output-from-value-literal
  (with-redefs [http/call (fn [_] (http/ok {}))]
    (let [endpoint (assoc base-endpoint :output_mapping {:ver {:source "value" :value "2.0"}})
          {:keys [data]} (run endpoint {} {})]
      (is (= "2.0" (:ver data))))))

(deftest test-run-endpoint-output-from-request-ctx
  (with-redefs [http/call (fn [_] (http/ok {}))]
    (let [endpoint (assoc base-endpoint :output_mapping {:rid {:source "ctx" :key "x-request-id"}})
          {:keys [data]} (run endpoint {} {:x-request-id "req-99"})]
      (is (= "req-99" (:rid data))))))

(deftest test-run-endpoint-output-from-step-plain-key
  (with-redefs [http/call (fn [_] (http/ok {:user-id "u1"}))]
    (let [endpoint (assoc base-endpoint :output_mapping {:userId {:source "step" :step_id "s" :key "user-id"}})
          {:keys [data]} (run endpoint {} {})]
      (is (= "u1" (:userId data))))))

(deftest test-run-endpoint-output-from-step-jq
  (with-redefs [http/call (fn [_] (http/ok {:profile {:name "Bob"}}))]
    (let [endpoint (assoc base-endpoint
                          :output_mapping {:name {:source "step" :step_id "s"
                                                  :jq ".profile.name"
                                                  :compiled-jq (jq/compile-query ".profile.name")}})
          {:keys [data]} (run endpoint {} {})]
      (is (= "Bob" (:name data))))))

(deftest test-run-endpoint-unresolvable-step-field-is-nil
  (with-redefs [http/call (fn [_] (http/err :not-found "404"))]
    (let [endpoint (assoc base-endpoint :output_mapping {:id {:source "step" :step_id "s" :key "id"}})
          {:keys [data]} (run endpoint {} {})]
      (is (nil? (:id data))))))

;; ---------------------------------------------------------------------------
;; run-endpoint — errors
;; ---------------------------------------------------------------------------

(deftest test-run-endpoint-step-error-in-errors
  (with-redefs [http/call (fn [_] (http/err :not-found "404"))]
    (let [{:keys [errors]} (run base-endpoint {} {})]
      (is (= 1 (count errors)))
      (is (= :not-found (get-in (first errors) [:extensions :code]))))))

(deftest test-run-endpoint-error-step-name-in-extensions
  (with-redefs [http/call (fn [_] (http/err :timeout "timeout"))]
    (let [{:keys [errors]} (run base-endpoint {} {})]
      (is (= "s" (get-in (first errors) [:extensions :step]))))))

(deftest test-run-endpoint-success-has-empty-errors
  (with-redefs [http/call (fn [_] (http/ok {:x 1}))]
    (let [{:keys [errors]} (run base-endpoint {} {})]
      (is (empty? errors)))))

;; ---------------------------------------------------------------------------
;; Request context header forwarding
;; ---------------------------------------------------------------------------

(deftest test-request-ctx-headers-forwarded-to-backend
  (testing "keys in request-ctx are forwarded as headers to backend calls"
    (let [captured (atom nil)]
      (with-redefs [http/call (fn [{:keys [headers]}]
                                (reset! captured headers)
                                (http/ok {}))]
        (graph [(assoc base-step :id "s")]
               {}
               {:authorization "Bearer tok" :x-tenant-id "tenant-1"})
        (is (= "Bearer tok" (get @captured "authorization")))
        (is (= "tenant-1"   (get @captured "x-tenant-id")))))))

(deftest test-remote-addr-forwarded-to-backend
  (testing "remote-addr from request-ctx is forwarded as a header"
    (let [captured (atom nil)]
      (with-redefs [http/call (fn [{:keys [headers]}]
                                (reset! captured headers)
                                (http/ok {}))]
        (graph [(assoc base-step :id "s")] {} {:remote-addr "1.2.3.4"})
        (is (= "1.2.3.4" (get @captured "remote-addr")))))))

(deftest test-nil-ctx-values-not-forwarded-as-headers
  (testing "nil ctx values are dropped before forwarding"
    (let [captured (atom nil)]
      (with-redefs [http/call (fn [{:keys [headers]}]
                                (reset! captured headers)
                                (http/ok {}))]
        (graph [(assoc base-step :id "s")]
               {} {:authorization nil :x-tenant-id "t1"})
        (is (not (contains? @captured "authorization")))
        (is (= "t1" (get @captured "x-tenant-id")))))))

(deftest test-step-extra-headers-merged-with-ctx-headers
  (testing "extra_headers on a step are merged with forwarded request-ctx headers"
    (let [captured (atom nil)]
      (with-redefs [http/call (fn [{:keys [headers]}]
                                (reset! captured headers)
                                (http/ok {}))]
        (graph [(assoc base-step :id "s" :extra_headers {"x-service-key" "secret"})]
               {} {:authorization "Bearer tok"})
        (is (= "Bearer tok" (get @captured "authorization")))
        (is (= "secret"     (get @captured "x-service-key")))))))

;; ---------------------------------------------------------------------------
;; Transformer dispatch — via `:transformers` in the extensions map
;; ---------------------------------------------------------------------------

(deftest test-transformer-registered-by-key-is-called
  (with-redefs [http/call (fn [_] (http/ok {}))]
    (let [endpoint (assoc base-endpoint :transformer {:key "test-add-flag"})
          {:keys [data]} (run-with-exts endpoint {} {}
                           {:transformers {"test-add-flag" (fn [_ _ m] (assoc m :flag true))}})]
      (is (true? (:flag data))))))

(deftest test-transformer-receives-args-and-chain-ctx
  (let [received (atom nil)]
    (with-redefs [http/call (fn [_] (http/ok {}))]
      (run-with-exts
        (assoc base-endpoint :transformer {:key "test-capture"})
        {:userId "u1"} {}
        {:transformers {"test-capture" (fn [args chain-ctx m]
                                         (reset! received {:args args :chain-ctx chain-ctx})
                                         m)}})
      (is (= {:userId "u1"} (:args @received)))
      (is (contains? (:chain-ctx @received) :s)))))

(deftest test-transformer-unknown-key-throws
  (with-redefs [http/call (fn [_] (http/ok {}))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (run (assoc base-endpoint :transformer {:key "definitely-not-registered"})
                      {} {})))))

(deftest test-transformer-unknown-key-ex-data-has-key
  (with-redefs [http/call (fn [_] (http/ok {}))]
    (try
      (run (assoc base-endpoint :transformer {:key "definitely-not-registered-2"}) {} {})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= "definitely-not-registered-2" (:key (ex-data e))))))))

(deftest test-transformer-ns-fn-form-resolves-and-calls
  (with-redefs [http/call (fn [_] (http/ok {}))]
    (let [endpoint (assoc base-endpoint
                          :transformer {:ns "bff.executor-test" :fn "test-transformer-fn"})
          {:keys [data]} (run endpoint {} {})]
      (is (true? (:via-ns data))))))

(deftest test-transformer-protocol-implementation
  (testing "a protocol implementation works as a transformer"
    (let [impl (reify executor/BffTransformer
                 (transform [_ _ _ m] (assoc m :via-protocol true)))]
      (with-redefs [http/call (fn [_] (http/ok {}))]
        (let [endpoint (assoc base-endpoint :transformer {:key "test-protocol-impl"})
              {:keys [data]} (run-with-exts endpoint {} {}
                               {:transformers {"test-protocol-impl" impl}})]
          (is (true? (:via-protocol data))))))))

(deftest test-no-transformer-returns-mapped-output-unchanged
  (with-redefs [http/call (fn [_] (http/ok {}))]
    (let [endpoint (assoc base-endpoint :output_mapping {:v {:source "value" :value 42}})
          {:keys [data]} (run endpoint {} {})]
      (is (= 42 (:v data))))))

(deftest test-run-endpoint-nested-output-mapping
  (with-redefs [http/call (fn [_] (http/ok {:name "Bob" :city "NY"}))]
    (let [endpoint (assoc base-endpoint
                          :output_mapping
                          {:profile {:name     {:source "step" :step_id "s" :key "name"}
                                     :location {:city {:source "step" :step_id "s" :key "city"}}}})
          {:keys [data]} (run endpoint {} {})]
      (is (= "Bob" (get-in data [:profile :name])))
      (is (= "NY"  (get-in data [:profile :location :city]))))))

;; ---------------------------------------------------------------------------
;; Resolver dispatch — via `:resolvers` in the extensions map
;; ---------------------------------------------------------------------------

(defn test-resolver-fn [args ctx]
  {:data {:fromResolver true :userId (:userId args) :tenant (:x-tenant-id ctx)}
   :errors []})

(deftest test-resolver-key-bypasses-backend-chain
  (let [called (atom false)]
    (with-redefs [http/call (fn [_] (reset! called true) (http/ok {}))]
      (run-with-exts {:resolver {:key "test-bypass"}} {} {}
                     {:resolvers {"test-bypass" (fn [_ _] {:data {:ok true} :errors []})}})
      (is (false? @called) "backend chain must not be called when resolver is present"))))

(deftest test-resolver-receives-args-and-ctx
  (let [{:keys [data]} (run-with-exts
                         {:resolver {:key "test-resolver-args"}}
                         {:userId "u1"} {:authorization "Bearer tok"}
                         {:resolvers {"test-resolver-args"
                                      (fn [args ctx]
                                        {:data {:a (:userId args)
                                                :c (:authorization ctx)}
                                         :errors []})}})]
    (is (= "u1"         (:a data)))
    (is (= "Bearer tok" (:c data)))))

(deftest test-resolver-errors-returned-as-is
  (let [{:keys [errors]} (run-with-exts
                           {:resolver {:key "test-resolver-errors"}} {} {}
                           {:resolvers {"test-resolver-errors"
                                        (fn [_ _]
                                          {:data {}
                                           :errors [{:message "something went wrong"}]})}})]
    (is (= 1 (count errors)))
    (is (= "something went wrong" (:message (first errors))))))

(deftest test-resolver-unknown-key-throws
  (is (thrown? clojure.lang.ExceptionInfo
               (run {:resolver {:key "definitely-not-registered-resolver"}} {} {}))))

(deftest test-resolver-ns-fn-form-resolves-and-calls
  (let [{:keys [data]} (run
                         {:resolver {:ns "bff.executor-test" :fn "test-resolver-fn"}}
                         {:userId "u99"} {:x-tenant-id "t1"})]
    (is (true?    (:fromResolver data)))
    (is (= "u99"  (:userId data)))
    (is (= "t1"   (:tenant data)))))

(deftest test-resolver-protocol-implementation
  (let [impl (reify executor/BffResolver
               (resolve-endpoint [_ args _ctx]
                 {:data {:via-protocol true :id (:id args)} :errors []}))
        {:keys [data]} (run-with-exts
                         {:resolver {:key "test-protocol-resolver"}}
                         {:id "x1"} {}
                         {:resolvers {"test-protocol-resolver" impl}})]
    (is (true?  (:via-protocol data)))
    (is (= "x1" (:id data)))))

;; ---------------------------------------------------------------------------
;; Java interface — io.github.rthadani.bff.BffTransformer / BffResolver
;; ---------------------------------------------------------------------------

(deftest test-java-interface-transformer-receives-string-keys
  (let [seen (atom nil)
        impl (reify io.github.rthadani.bff.BffTransformer
               (transform [_ args _chain _mapped]
                 (reset! seen args)
                 (doto (java.util.HashMap.) (.put "flag" true))))]
    (with-redefs [http/call (fn [_] (http/ok {}))]
      (let [{:keys [data]} (run-with-exts
                             (assoc base-endpoint :transformer {:key "test-java-transformer"})
                             {:userId "u1"} {}
                             {:transformers {"test-java-transformer" impl}})]
        (is (instance? java.util.Map @seen))
        (is (= "u1"  (.get ^java.util.Map @seen "userId")))
        (is (true? (:flag data)))))))

(deftest test-java-interface-transformer-sees-step-status-as-string
  (testing "step results in chainCtx have string statuses, not clojure Keywords"
    (let [seen (atom nil)
          impl (reify io.github.rthadani.bff.BffTransformer
                 (transform [_ _args chain-ctx mapped]
                   (reset! seen chain-ctx)
                   mapped))]
      (with-redefs [http/call (fn [_] (http/ok {}))]
        (run-with-exts
          (assoc base-endpoint :transformer {:key "test-java-chain"})
          {} {}
          {:transformers {"test-java-chain" impl}})
        (let [step (.get ^java.util.Map @seen "s")]
          (is (instance? java.util.Map step))
          (is (= "ok" (.get ^java.util.Map step "status"))))))))

(deftest test-java-interface-resolver-returns-string-keyed-map
  (let [impl (reify io.github.rthadani.bff.BffResolver
               (resolve [_ args _ctx]
                 (doto (java.util.HashMap.)
                   (.put "data"   (doto (java.util.HashMap.)
                                    (.put "fromJava" true)
                                    (.put "id"       (.get ^java.util.Map args "id"))))
                   (.put "errors" (java.util.List/of)))))
        {:keys [data errors]} (run-with-exts
                                {:resolver {:key "test-java-resolver"}}
                                {:id "x1"} {}
                                {:resolvers {"test-java-resolver" impl}})]
    (is (true?  (:fromJava data)))
    (is (= "x1" (:id data)))
    (is (empty? errors))))

(deftest test-java-interface-resolver-errors-keywordized
  (let [impl (reify io.github.rthadani.bff.BffResolver
               (resolve [_ _args _ctx]
                 (doto (java.util.HashMap.)
                   (.put "data" nil)
                   (.put "errors" (java.util.List/of
                                    (doto (java.util.HashMap.)
                                      (.put "message" "java-side failure")))))))
        {:keys [errors]} (run-with-exts
                           {:resolver {:key "test-java-resolver-err"}} {} {}
                           {:resolvers {"test-java-resolver-err" impl}})]
    (is (= 1 (count errors)))
    (is (= "java-side failure" (:message (first errors))))))

(deftest test-resolver-as-step-feeds-downstream-output-mapping
  (testing "a resolver embedded as a chain step exposes :data to output_mapping"
    (with-redefs [http/call (fn [_] (http/ok {:speeds [10.0 20.0 30.0]}))]
      (let [compute (fn [args _ctx]
                      {:data   {:mean (/ (reduce + (:speeds args)) (count (:speeds args)))
                                :n    (count (:speeds args))}
                       :errors []})
            endpoint {:backend_chain
                      [(assoc base-step :id "fetch")
                       {:id            "efficiency"
                        :resolver      {:key "compute-mean"}
                        :deps          ["fetch"]
                        :input_mapping {:speeds {:source "step"
                                                 :step_id "fetch"
                                                 :jq ".speeds"
                                                 :compiled-jq (jq/compile-query ".speeds")}}}]
                      :output_mapping
                      {:mean {:source "step" :step_id "efficiency"
                              :jq ".mean" :compiled-jq (jq/compile-query ".mean")}
                       :n    {:source "step" :step_id "efficiency"
                              :jq ".n"    :compiled-jq (jq/compile-query ".n")}}}
            {:keys [data errors]} (run-with-exts endpoint {} {}
                                                 {:resolvers {"compute-mean" compute}})]
        (is (empty? errors))
        (is (= 20.0 (:mean data)))
        (is (= 3    (:n data)))))))

(deftest test-resolver-as-step-errors-surface-through-chain
  (testing "resolver returning errors marks the step failed and surfaces the error"
    (with-redefs [http/call (fn [_] (http/ok {:x 1}))]
      (let [bad (fn [_args _ctx]
                  {:data nil :errors [{:message "boom"}]})
            endpoint {:backend_chain
                      [(assoc base-step :id "fetch")
                       {:id       "compute"
                        :resolver {:key "bad-resolver"}
                        :deps     ["fetch"]
                        :critical true}]
                      :output_mapping {}}]
        (is (thrown? clojure.lang.ExceptionInfo
              (run-with-exts endpoint {} {}
                             {:resolvers {"bad-resolver" bad}})))))))

(deftest test-run-endpoint-nested-output-mapping-with-jq
  (with-redefs [http/call (fn [_] (http/ok {:user {:id "u1" :score 99}}))]
    (let [endpoint (assoc base-endpoint
                          :output_mapping
                          {:summary {:userId {:source "step" :step_id "s"
                                              :jq ".user.id"
                                              :compiled-jq (jq/compile-query ".user.id")}
                                     :score  {:source "step" :step_id "s"
                                              :jq ".user.score"
                                              :compiled-jq (jq/compile-query ".user.score")}}})
          {:keys [data]} (run endpoint {} {})]
      (is (= "u1" (get-in data [:summary :userId])))
      (is (= 99   (get-in data [:summary :score]))))))
