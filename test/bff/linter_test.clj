(ns bff.linter-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [bff.linter :as linter]))

(deftest test-problem-shape-round-trips
  (is (linter/problem? {:severity :error
                        :path     "endpoints[0]"
                        :message  "missing :name"
                        :node     {:type "query"}}))
  (is (not (linter/problem? {:severity :error :path "x" :message "y"})))
  (is (not (linter/problem? {:severity :bogus :path "x" :message "y" :node {}}))))

(def ^:private minimal-spec
  {:endpoints
   [{:name "ping"
     :type "query"
     :output_type {:name "PingResult" :fields {:message "String!"}}}]})

(deftest test-minimal-spec-is-clean
  (is (= [] (linter/lint-spec minimal-spec))))

(deftest test-empty-spec-fails-missing-endpoints
  (is (= 1 (count (linter/lint-spec {})))))

(deftest test-endpoint-with-inline-and-string-output-types
  (let [spec (update minimal-spec :endpoints conj
                     {:name "shared" :type "query" :output_type "PingResult"})]
    (is (= [] (linter/lint-spec spec)))))

(deftest test-backend-chain-with-full-shape-is-clean
  (let [spec (update-in minimal-spec [:endpoints 0]
                        assoc :backend_chain
                        [{:id "s" :url "http://api" :method "GET"
                          :deps []
                          :retry {:max 1 :on_code [:unauthorized]}
                          :compensation {:url "http://undo" :method "DELETE"}}])]
    (is (= [] (linter/lint-spec spec)))))

(deftest test-resolver-step-without-url-is-clean
  (let [spec (update-in minimal-spec [:endpoints 0]
                        assoc :backend_chain
                        [{:id "s1" :url "http://api" :method "GET"}
                         {:id       "compute"
                          :resolver {:key "my-resolver"}
                          :deps     ["s1"]}])]
    (is (= [] (linter/lint-spec spec)))))

(deftest test-step-with-both-url-and-resolver-is-caught
  (let [spec (update-in minimal-spec [:endpoints 0]
                        assoc :backend_chain
                        [{:id       "bad"
                          :url      "http://api"
                          :method   "GET"
                          :resolver {:key "r"}}])]
    (is (= 1 (count (linter/lint-spec spec))))))

(deftest test-step-with-neither-url-nor-resolver-is-caught
  (let [spec (update-in minimal-spec [:endpoints 0]
                        assoc :backend_chain
                        [{:id "empty"}])]
    (is (= 1 (count (linter/lint-spec spec))))))

(deftest test-typo-key-on-backend-step-is-caught
  (let [spec (update-in minimal-spec [:endpoints 0]
                        assoc :backend_chain
                        [{:id "s" :url "http://api" :method "GET"
                          :retrys {:max 1}}])]
    (is (= 1 (count (linter/lint-spec spec))))))

(deftest test-invalid-method-is-caught
  (let [spec (update-in minimal-spec [:endpoints 0]
                        assoc :backend_chain
                        [{:id "s" :url "http://api" :method "TRACE"}])]
    (is (= 1 (count (linter/lint-spec spec))))))

(deftest test-typo-becomes-warning-with-key-path
  (let [step {:id "s" :url "http://api" :method "GET" :retrys {:max 1}}
        spec (update-in minimal-spec [:endpoints 0]
                        assoc :backend_chain [step])
        [p]  (linter/lint-spec spec)]
    (is (= :warning (:severity p)))
    (is (= "endpoints[0].backend_chain[0].retrys" (:path p)))
    (is (= step (:node p)))))

(deftest test-invalid-method-becomes-error
  (let [step {:id "s" :url "http://api" :method "TRACE"}
        spec (update-in minimal-spec [:endpoints 0]
                        assoc :backend_chain [step])
        [p]  (linter/lint-spec spec)]
    (is (= :error (:severity p)))
    (is (= "endpoints[0].backend_chain[0].method" (:path p)))
    (is (= step (:node p)))))

(deftest test-missing-endpoints-points-at-spec
  (let [[p] (linter/lint-spec {})]
    (is (= :error (:severity p)))
    (is (= {} (:node p)))))

(deftest test-multiple-typos-produce-multiple-problems
  (let [spec (update-in minimal-spec [:endpoints 0]
                        assoc :backend_chain
                        [{:id "s" :url "http://api" :method "GET" :retrys 1 :cach "x"}])
        problems (linter/lint-spec spec)]
    (is (= 2 (count problems)))
    (is (every? #(= :warning (:severity %)) problems))))

(defn- with-chain [spec chain]
  (update-in spec [:endpoints 0] assoc :backend_chain chain))

(deftest test-duplicate-step-ids-are-flagged
  (let [spec (with-chain minimal-spec
                         [{:id "a" :url "http://x" :method "GET"}
                          {:id "a" :url "http://y" :method "GET"}])
        [p] (linter/lint-spec spec)]
    (is (= :error (:severity p)))
    (is (str/includes? (:message p) "duplicate step id"))))

(deftest test-deps-referencing-unknown-step-is-flagged
  (let [spec (with-chain minimal-spec
                         [{:id "a" :url "http://x" :method "GET"}
                          {:id "b" :url "http://y" :method "GET" :deps ["nope"]}])
        [p] (linter/lint-spec spec)]
    (is (= "endpoints[0].backend_chain[1].deps[0]" (:path p)))
    (is (str/includes? (:message p) "unknown step 'nope'"))))

(deftest test-step-ref-in-input-mapping-is-flagged
  (let [spec (with-chain minimal-spec
                         [{:id "a" :url "http://x" :method "GET"
                           :input_mapping {:id {:source "step" :step_id "ghost" :key "x"}}}])]
    (is (= 1 (count (linter/lint-spec spec))))))

(deftest test-arg-ref-not-declared-is-flagged
  (let [spec (with-chain minimal-spec
                         [{:id "a" :url "http://x" :method "GET"
                           :input_mapping {:id {:source "args" :key "userId"}}}])]
    (is (= 1 (count (linter/lint-spec spec))))))

(deftest test-arg-ref-declared-is-clean
  (let [spec (-> minimal-spec
                 (assoc-in [:endpoints 0 :args] {:userId {:type "String!"}})
                 (with-chain [{:id "a" :url "http://x" :method "GET"
                               :input_mapping {:id {:source "args" :key "userId"}}}]))]
    (is (= [] (linter/lint-spec spec)))))

(deftest test-unknown-type-in-output-fields-is-flagged
  (let [spec (assoc-in minimal-spec [:endpoints 0 :output_type :fields :other] "Widget")]
    (is (= 1 (count (linter/lint-spec spec))))))

(deftest test-top-level-output-type-ref-resolves
  (let [spec {:output_types [{:name "Widget" :fields {:sku "String!"}}]
              :endpoints [{:name "op" :type "query" :output_type "Widget"}]}]
    (is (= [] (linter/lint-spec spec)))))

(deftest test-scalar-declared-in-scalars-resolves
  (let [spec (-> minimal-spec
                 (assoc :scalars [{:name "DateTime"}])
                 (assoc-in [:endpoints 0 :output_type :fields :when] "DateTime!"))]
    (is (= [] (linter/lint-spec spec)))))

(deftest test-two-output-types-with-matching-fields-are-clean
  (let [spec {:output_types [{:name "User" :fields {:id "String!"}}]
              :endpoints [{:name "op1" :type "query"
                           :output_type {:name "User" :fields {:id "String!"}}}]}]
    (is (= [] (linter/lint-spec spec)))))

(deftest test-two-output-types-with-conflicting-field-flagged
  (let [spec {:output_types [{:name "User" :fields {:id "String!"}}]
              :endpoints [{:name "op1" :type "query"
                           :output_type {:name "User" :fields {:id "Int!"}}}]}
        [p]  (linter/lint-spec spec)]
    (is (= :error (:severity p)))
    (is (str/includes? (:message p) "id"))
    (is (str/includes? (:message p) "User"))))

(deftest test-input-type-conflict-flagged
  (let [spec (assoc minimal-spec :input_types
                    [{:name "OrderInput" :fields {:sku "String!"}}
                     {:name "OrderInput" :fields {:sku "Int!"}}])]
    (is (= 1 (count (linter/lint-spec spec))))))

(deftest test-three-defs-with-two-conflicts-produce-two-problems
  (let [spec {:output_types [{:name "T" :fields {:a "String!"}}
                             {:name "T" :fields {:a "Int!"}}
                             {:name "T" :fields {:a "Boolean!"}}]
              :endpoints [{:name "op" :type "query" :output_type "T"}]}]
    (is (= 2 (count (linter/lint-spec spec))))))

(deftest test-def1-def3-conflict-with-def2-not-mentioning-field
  (let [spec {:output_types [{:name "T" :fields {:a "String!"}}
                             {:name "T" :fields {:b "Int!"}}
                             {:name "T" :fields {:a "Boolean!"}}]
              :endpoints [{:name "op" :type "query" :output_type "T"}]}]
    (is (= 1 (count (linter/lint-spec spec))))))

(deftest test-valid-jq-is-clean
  (let [spec (with-chain minimal-spec
                         [{:id "a" :url "http://x" :method "GET"
                           :body_mapping {:id {:source "step" :step_id "a" :jq ".data.id"}}}])]
    (is (= [] (linter/lint-spec spec)))))

(deftest test-bad-jq-in-body-mapping-flagged
  (let [spec (with-chain minimal-spec
                         [{:id "a" :url "http://x" :method "GET"
                           :body_mapping {:id {:source "step" :step_id "a" :jq ".x[|"}}}])
        [p]  (linter/lint-spec spec)]
    (is (= :error (:severity p)))
    (is (str/includes? (:message p) "jq failed"))))

(deftest test-bad-jq-in-compensation-flagged
  (let [spec (with-chain minimal-spec
                         [{:id "a" :url "http://x" :method "GET"
                           :compensation {:url "http://undo" :method "DELETE"
                                          :input_mapping
                                          {:id {:source "step" :step_id "a" :jq ".x[|"}}}}])]
    (is (= 1 (count (linter/lint-spec spec))))))

(deftest test-bad-jq-in-nested-output-mapping-flagged
  (let [spec (-> minimal-spec
                 (with-chain [{:id "a" :url "http://x" :method "GET"}])
                 (assoc-in [:endpoints 0 :output_mapping]
                           {:profile {:id {:source "step" :step_id "a" :jq ".{{{"}}}))]
    (is (= 1 (count (linter/lint-spec spec))))))

(deftest test-lint-file-against-demo-spec
  (let [problems (linter/lint-file "resources/bff-spec.yaml")
        paths    (set (map :path problems))]
    (is (= 2 (count problems)))
    (is (contains? paths "endpoints[0].backend_chain[2].input_mapping.limit.source"))
    (is (contains? paths "endpoints[1].backend_chain[1].body_mapping.template.source"))
    (is (every? #(= :error (:severity %)) problems))))

(deftest test-kitchen-sink-composed-problems
  (let [spec {:input_types [{:name "OrderInput" :fields {:sku "String!"}}
                            {:name "OrderInput" :fields {:sku "Int!"}}]
              :endpoints
              [{:name "op"
                :type "query"
                :args {:userId {:type "String!"}}
                :output_type {:name "R"
                              :fields {:id "String!" :other "Widget"}}
                :backend_chain
                [{:id "a" :url "http://x" :method "GET"}
                 {:id "a" :url "http://y" :method "GET"
                  :deps ["ghost"]
                  :body_mapping {:x {:source "args" :key "missing"}
                                 :y {:source "step" :step_id "nope" :jq ".{"}}}]}]}
        problems (linter/lint-spec spec)
        by-kind  (fn [substr] (filter #(str/includes? (:message %) substr) problems))]
    (is (seq (by-kind "conflicting"))
        "input_type merge conflict")
    (is (seq (by-kind "duplicate step id"))
        "duplicate step id")
    (is (seq (by-kind "unknown step 'ghost'"))
        "deps unknown")
    (is (seq (by-kind "unknown step 'nope'"))
        "step ref unknown")
    (is (seq (by-kind "unknown arg 'missing'"))
        "arg ref unknown")
    (is (seq (by-kind "unknown type 'Widget'"))
        "type ref unknown")
    (is (seq (by-kind "jq failed"))
        "bad jq")))

(deftest test-format-problem-contains-all-parts
  (let [p {:severity :warning
           :path     "endpoints[0].name"
           :message  "example message"
           :node     {:id "s" :url "http://x"}}
        out (#'linter/format-problem p)]
    (is (str/includes? out "WARNING"))
    (is (str/includes? out "endpoints[0].name"))
    (is (str/includes? out "example message"))
    (is (str/includes? out ":id \"s\""))))
