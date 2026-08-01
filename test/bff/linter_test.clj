(ns bff.linter-test
  (:require [clojure.test :refer [deftest is]]
            [bff.linter :as linter]))

(deftest test-problem-shape-round-trips
  (is (linter/problem? {:severity :error
                        :path     "endpoints[0]"
                        :message  "missing :name"
                        :node     {:type "query"}}))
  (is (not (linter/problem? {:severity :error :path "x" :message "y"})))
  (is (not (linter/problem? {:severity :bogus :path "x" :message "y" :node {}}))))

;; --------------------------------------------------------------------------
;; Structural — schema validation
;; --------------------------------------------------------------------------

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
