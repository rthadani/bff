(ns bff.spec-loader-test
  (:require [clojure.test :refer [deftest is testing]]
            [bff.spec-loader :as loader]))

;; ---------------------------------------------------------------------------
;; In-memory fixture
;;
;; Mirrors the structure produced by clj-yaml when parsing the YAML fixture,
;; used for compile-spec tests that don't need file I/O.
;; ---------------------------------------------------------------------------

(def ^:private fixture-spec
  {:endpoints
   [{:name "getUser"
     :type "query"
     :backend_chain
     [{:id "fetch_user"
       :url "https://api.example.com/users/{userId}"
       :method "GET"
       :deps []
       :critical true
       :input_mapping
       {:userId {:source "args" :key "userId"}}}       ; no :jq

      {:id "fetch_profile"
       :url "https://api.example.com/profile"
       :method "GET"
       :deps ["fetch_user"]
       :input_mapping
       {:internal_id {:source "step" :step_id "fetch_user"
                      :jq ".data.internal_id"}}}]      ; has :jq

     :output_mapping
     {:id   {:source "step" :step_id "fetch_user" :jq ".data.id"}   ; has :jq
      :name {:source "step" :step_id "fetch_user" :key "name"}}}    ; no :jq

    {:name "createOrder"
     :type "mutation"
     :backend_chain
     [{:id "create_order"
       :url "https://api.example.com/orders"
       :method "POST"
       :deps []
       :critical true
       :body_mapping
       {:user_id {:source "args" :key "userId"}}}      ; no :jq

      {:id "notify_user"
       :url "https://api.example.com/notify"
       :method "POST"
       :deps ["create_order"]
       :body_mapping
       {:order_id {:source "step" :step_id "create_order"
                   :jq ".data.id"}}}]                  ; has :jq

     :output_mapping
     {:orderId {:source "step" :step_id "create_order"
                :jq ".data.order_id"}}}]})

(deftest test-compile-spec-preserves-endpoint-count
  (testing "compile-spec does not add or remove endpoints"
    (is (= 2 (count (:endpoints (loader/compile-spec fixture-spec)))))))

(deftest test-compile-spec-output-mapping-jq-gets-compiled-jq
  (testing "output_mapping entry with :jq receives a :compiled-jq key"
    (let [endpoint (first (:endpoints (loader/compile-spec fixture-spec)))
          mapping  (:id (:output_mapping endpoint))]
      (is (contains? mapping :compiled-jq))
      (is (some? (:compiled-jq mapping))))))

(deftest test-compile-spec-output-mapping-no-jq-unchanged
  (testing "output_mapping entry without :jq does not get :compiled-jq"
    (let [endpoint (first (:endpoints (loader/compile-spec fixture-spec)))
          mapping  (:name (:output_mapping endpoint))]
      (is (not (contains? mapping :compiled-jq))))))

(deftest test-compile-spec-input-mapping-jq-gets-compiled-jq
  (testing "step input_mapping entry with :jq receives a :compiled-jq key"
    (let [endpoint (first (:endpoints (loader/compile-spec fixture-spec)))
          step     (second (:backend_chain endpoint))
          mapping  (:internal_id (:input_mapping step))]
      (is (contains? mapping :compiled-jq))
      (is (some? (:compiled-jq mapping))))))

(deftest test-compile-spec-input-mapping-no-jq-unchanged
  (testing "step input_mapping entry without :jq does not get :compiled-jq"
    (let [endpoint (first (:endpoints (loader/compile-spec fixture-spec)))
          step     (first (:backend_chain endpoint))
          mapping  (:userId (:input_mapping step))]
      (is (not (contains? mapping :compiled-jq))))))

(deftest test-compile-spec-body-mapping-jq-gets-compiled-jq
  (testing "step body_mapping entry with :jq receives a :compiled-jq key"
    (let [endpoint (second (:endpoints (loader/compile-spec fixture-spec)))
          step     (second (:backend_chain endpoint))
          mapping  (:order_id (:body_mapping step))]
      (is (contains? mapping :compiled-jq))
      (is (some? (:compiled-jq mapping))))))

(deftest test-compile-spec-body-mapping-no-jq-unchanged
  (testing "step body_mapping entry without :jq does not get :compiled-jq"
    (let [endpoint (second (:endpoints (loader/compile-spec fixture-spec)))
          step     (first (:backend_chain endpoint))
          mapping  (:user_id (:body_mapping step))]
      (is (not (contains? mapping :compiled-jq))))))

(deftest test-compile-spec-preserves-jq-string
  (testing "The original :jq string is preserved alongside :compiled-jq"
    (let [endpoint (first (:endpoints (loader/compile-spec fixture-spec)))
          mapping  (:id (:output_mapping endpoint))]
      (is (= ".data.id" (:jq mapping))))))

(deftest test-compile-spec-compiled-jq-type
  (testing ":compiled-jq is a net.thisptr.jackson.jq.JsonQuery instance"
    (let [endpoint (first (:endpoints (loader/compile-spec fixture-spec)))
          mapping  (:id (:output_mapping endpoint))]
      (is (instance? net.thisptr.jackson.jq.JsonQuery
                     (:compiled-jq mapping))))))

(deftest test-compile-spec-nil-mapping-is-safe
  (testing "compile-spec tolerates steps with no input_mapping or body_mapping"
    (let [spec {:endpoints [{:name "bare"
                              :type "query"
                              :backend_chain [{:id "s" :url "http://x" :method "GET"
                                               :deps []}]
                              :output_mapping {}}]}]
      (is (= 1 (count (:endpoints (loader/compile-spec spec))))))))


(deftest test-load-spec-throws-on-missing-resource
  (testing "load-spec throws ExceptionInfo when the resource is not found"
    (is (thrown? clojure.lang.ExceptionInfo
                 (loader/load-spec "does-not-exist.yaml")))))

(deftest test-load-spec-exception-message
  (testing "Exception message names the missing file"
    (try
      (loader/load-spec "does-not-exist.yaml")
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (re-find #"does-not-exist\.yaml" (ex-message e)))))))

(deftest test-load-spec-exception-data-contains-path
  (testing "ex-data contains :path with the requested resource path"
    (try
      (loader/load-spec "does-not-exist.yaml")
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= "does-not-exist.yaml" (:path (ex-data e))))))))


(deftest test-load-spec-returns-map-with-endpoints
  (testing "load-spec parses YAML into a Clojure map with :endpoints"
    (let [spec (loader/load-spec "spec-loader-fixture.yaml")]
      (is (map? spec))
      (is (contains? spec :endpoints)))))

(deftest test-load-spec-endpoint-count
  (testing "Fixture has two endpoints"
    (is (= 2 (count (:endpoints (loader/load-spec "spec-loader-fixture.yaml")))))))

(deftest test-load-spec-endpoint-names
  (testing "Endpoint names are parsed correctly"
    (let [names (->> (loader/load-spec "spec-loader-fixture.yaml")
                     :endpoints
                     (map :name)
                     set)]
      (is (= #{"getUser" "createOrder"} names)))))

(deftest test-load-spec-endpoint-types
  (testing "Endpoint types are parsed correctly"
    (let [endpoints (:endpoints (loader/load-spec "spec-loader-fixture.yaml"))
          types     (set (map :type endpoints))]
      (is (contains? types "query"))
      (is (contains? types "mutation")))))

(deftest test-load-spec-backend-chain-count
  (testing "Each endpoint has two backend_chain steps"
    (doseq [ep (:endpoints (loader/load-spec "spec-loader-fixture.yaml"))]
      (is (= 2 (count (:backend_chain ep)))
          (str "endpoint: " (:name ep))))))

(deftest test-load-spec-step-deps-are-sequential
  (testing "Step deps lists parse as sequential collections"
    (let [steps (-> (loader/load-spec "spec-loader-fixture.yaml")
                    :endpoints first :backend_chain)]
      (is (sequential? (:deps (first steps))))
      (is (sequential? (:deps (second steps)))))))

(deftest test-load-spec-critical-flag
  (testing "critical: true parses as boolean true"
    (let [step (-> (loader/load-spec "spec-loader-fixture.yaml")
                   :endpoints first :backend_chain first)]
      (is (true? (:critical step))))))

(deftest test-load-spec-raw-jq-strings-not-compiled
  (testing "load-spec alone does not compile jq — :compiled-jq is absent"
    (let [endpoint (-> (loader/load-spec "spec-loader-fixture.yaml")
                       :endpoints first)
          id-mapping (:id (:output_mapping endpoint))]
      (is (contains? id-mapping :jq))
      (is (not (contains? id-mapping :compiled-jq))))))


(deftest test-load-and-compile-output-mapping-jq-compiled
  (testing "load-and-compile produces :compiled-jq on output_mapping jq entries"
    (let [endpoint (-> (loader/load-and-compile "spec-loader-fixture.yaml")
                       :endpoints first)
          mapping  (:id (:output_mapping endpoint))]
      (is (contains? mapping :compiled-jq))
      (is (instance? net.thisptr.jackson.jq.JsonQuery (:compiled-jq mapping))))))

(deftest test-load-and-compile-output-mapping-plain-unchanged
  (testing "load-and-compile leaves non-jq output_mapping entries without :compiled-jq"
    (let [endpoint (-> (loader/load-and-compile "spec-loader-fixture.yaml")
                       :endpoints first)
          mapping  (:name (:output_mapping endpoint))]
      (is (not (contains? mapping :compiled-jq))))))

(deftest test-load-and-compile-input-mapping-jq-compiled
  (testing "load-and-compile produces :compiled-jq on step input_mapping jq entries"
    (let [step    (-> (loader/load-and-compile "spec-loader-fixture.yaml")
                      :endpoints first :backend_chain second)
          mapping (:internal_id (:input_mapping step))]
      (is (contains? mapping :compiled-jq))
      (is (instance? net.thisptr.jackson.jq.JsonQuery (:compiled-jq mapping))))))

(deftest test-load-and-compile-body-mapping-jq-compiled
  (testing "load-and-compile produces :compiled-jq on step body_mapping jq entries"
    (let [step    (-> (loader/load-and-compile "spec-loader-fixture.yaml")
                      :endpoints second :backend_chain second)
          mapping (:order_id (:body_mapping step))]
      (is (contains? mapping :compiled-jq))
      (is (instance? net.thisptr.jackson.jq.JsonQuery (:compiled-jq mapping))))))

(deftest test-load-and-compile-plain-input-mapping-unchanged
  (testing "load-and-compile does not add :compiled-jq to non-jq step mappings"
    (let [step    (-> (loader/load-and-compile "spec-loader-fixture.yaml")
                      :endpoints first :backend_chain first)
          mapping (:userId (:input_mapping step))]
      (is (= "args" (:source mapping)))
      (is (not (contains? mapping :compiled-jq))))))
