(ns bff.shared-types-test
  "Top-level output_types, plus conflict-checked merging of duplicate input
   and output type definitions."
  (:require [clojure.test :refer [deftest is testing]]
            [bff.schema-builder :as sb]
            [com.walmartlabs.lacinia :as lacinia]))

(defn- exec [schema q] (lacinia/execute schema q {} {}))

(defn- type-field-map [schema type-name]
  (let [q (str "{ __type(name: \"" type-name "\") { fields { name type { kind name } } } }")]
    (->> (get-in (exec schema q) [:data :__type :fields])
         (map (juxt :name identity))
         (into {}))))

(defn- input-field-map [schema type-name]
  (let [q (str "{ __type(name: \"" type-name "\") { inputFields { name type { kind name } } } }")]
    (->> (get-in (exec schema q) [:data :__type :inputFields])
         (map (juxt :name identity))
         (into {}))))

;; ---------------------------------------------------------------------------
;; Top-level output_types: — endpoint references by bare string
;; ---------------------------------------------------------------------------

(def ^:private shared-out-spec
  {:output_types [{:name "SharedResult"
                   :fields {:id     "String!"
                            :status "String"}}]
   :endpoints [{:name "opA" :type "query" :args {}
                :output_type "SharedResult"
                :backend_chain [] :output_mapping {}}
               {:name "opB" :type "query" :args {}
                :output_type "SharedResult"
                :backend_chain [] :output_mapping {}}]})

(deftest test-top-level-output-type-referenced-by-name
  (let [schema (sb/build-schema shared-out-spec)
        fields (type-field-map schema "SharedResult")]
    (is (contains? fields "id"))
    (is (contains? fields "status"))))

(deftest test-two-endpoints-share-one-output-type
  (let [schema (sb/build-schema shared-out-spec)
        result (exec schema "{ __schema { queryType { fields { name type { name } } } } }")
        queries (get-in result [:data :__schema :queryType :fields])
        types   (->> queries (map (comp :name :type)) set)]
    (is (= #{"SharedResult"} types) "both opA and opB return the same object type")))

;; ---------------------------------------------------------------------------
;; Merging: same type declared twice, disjoint fields
;; ---------------------------------------------------------------------------

(deftest test-inline-plus-top-level-merges-disjoint-fields
  (let [spec {:output_types [{:name "User" :fields {:id "String!"}}]
              :endpoints
              [{:name "getUser" :type "query" :args {}
                :output_type {:name "User" :fields {:email "String"}}
                :backend_chain [] :output_mapping {}}]}
        schema (sb/build-schema spec)
        fields (type-field-map schema "User")]
    (is (contains? fields "id"))
    (is (contains? fields "email"))))

(deftest test-two-inline-defs-with-disjoint-fields-merge
  (let [spec {:endpoints
              [{:name "a" :type "query" :args {}
                :output_type {:name "User" :fields {:id "String!"}}
                :backend_chain [] :output_mapping {}}
               {:name "b" :type "query" :args {}
                :output_type {:name "User" :fields {:name "String"}}
                :backend_chain [] :output_mapping {}}]}
        schema (sb/build-schema spec)
        fields (type-field-map schema "User")]
    (is (contains? fields "id"))
    (is (contains? fields "name"))))

;; ---------------------------------------------------------------------------
;; Merging: same type declared twice with identical shared field passes
;; ---------------------------------------------------------------------------

(deftest test-duplicate-defs-with-matching-shared-field-pass
  (let [spec {:endpoints
              [{:name "a" :type "query" :args {}
                :output_type {:name "User" :fields {:id "String!" :name "String"}}
                :backend_chain [] :output_mapping {}}
               {:name "b" :type "query" :args {}
                :output_type {:name "User" :fields {:id "String!" :email "String"}}
                :backend_chain [] :output_mapping {}}]}
        schema (sb/build-schema spec)
        fields (type-field-map schema "User")]
    (is (= 3 (count fields)))
    (is (every? #(contains? fields %) ["id" "name" "email"]))))

;; ---------------------------------------------------------------------------
;; Conflict: same field, different types
;; ---------------------------------------------------------------------------

(deftest test-conflicting-output-field-types-throw
  (let [spec {:endpoints
              [{:name "a" :type "query" :args {}
                :output_type {:name "User" :fields {:id "String!"}}
                :backend_chain [] :output_mapping {}}
               {:name "b" :type "query" :args {}
                :output_type {:name "User" :fields {:id "Int!"}}
                :backend_chain [] :output_mapping {}}]}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
          #"Conflicting types for field 'id' in output type 'User'"
          (sb/build-schema spec)))))

(deftest test-conflict-error-carries-details-in-ex-data
  (let [spec {:endpoints
              [{:name "a" :type "query" :args {}
                :output_type {:name "User" :fields {:id "String!"}}
                :backend_chain [] :output_mapping {}}
               {:name "b" :type "query" :args {}
                :output_type {:name "User" :fields {:id "Int!"}}
                :backend_chain [] :output_mapping {}}]}]
    (try (sb/build-schema spec)
         (is false "should have thrown")
         (catch clojure.lang.ExceptionInfo e
           (let [d (ex-data e)]
             (is (= :User (:type d)))
             (is (= :id   (:field d)))
             (is (= "output type" (:kind d))))))))

;; ---------------------------------------------------------------------------
;; Input types get the same merging + conflict treatment
;; ---------------------------------------------------------------------------

(deftest test-duplicate-input-types-merge
  (let [spec {:input_types [{:name "OrderInput" :fields {:sku "String!"}}
                            {:name "OrderInput" :fields {:qty "Int!"}}]
              :endpoints
              [{:name "op" :type "mutation"
                :args {:order {:type "OrderInput!"}}
                :output_type {:name "R" :fields {:ok "Boolean!"}}
                :backend_chain [] :output_mapping {}}]}
        schema (sb/build-schema spec)
        fields (input-field-map schema "OrderInput")]
    (is (contains? fields "sku"))
    (is (contains? fields "qty"))))

(deftest test-conflicting-input-field-types-throw
  (let [spec {:input_types [{:name "OrderInput" :fields {:sku "String!"}}
                            {:name "OrderInput" :fields {:sku "Int!"}}]
              :endpoints [{:name "op" :type "mutation"
                           :args {:order {:type "OrderInput!"}}
                           :output_type {:name "R" :fields {:ok "Boolean!"}}
                           :backend_chain [] :output_mapping {}}]}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
          #"Conflicting types for field 'sku' in input type 'OrderInput'"
          (sb/build-schema spec)))))

;; ---------------------------------------------------------------------------
;; Inline is still supported (no top-level entry)
;; ---------------------------------------------------------------------------

(deftest test-inline-only-still-works
  (let [spec {:endpoints
              [{:name "op" :type "query" :args {}
                :output_type {:name "Bare" :fields {:x "String"}}
                :backend_chain [] :output_mapping {}}]}
        schema (sb/build-schema spec)]
    (is (contains? (type-field-map schema "Bare") "x"))))

;; ---------------------------------------------------------------------------
;; Nested inline types across endpoints also merge
;; ---------------------------------------------------------------------------

(deftest test-nested-inline-types-across-endpoints-merge
  (let [nested-def {:name "Point" :fields {:x "Float!" :y "Float!"}}
        spec {:endpoints
              [{:name "opA" :type "query" :args {}
                :output_type {:name "Shape"
                              :fields {:start nested-def}}
                :backend_chain [] :output_mapping {}}
               {:name "opB" :type "query" :args {}
                :output_type {:name "Shape2"
                              :fields {:end nested-def}}
                :backend_chain [] :output_mapping {}}]}
        schema (sb/build-schema spec)
        fields (type-field-map schema "Point")]
    (is (contains? fields "x"))
    (is (contains? fields "y"))))
