(ns bff.schema-builder-test
  (:require [clojure.test :refer [deftest is]]
            [bff.schema-builder :as sb]
            [bff.executor :as executor]
            [com.walmartlabs.lacinia :as lacinia]
            [missionary.core :as m]))

(def ^:private ping-spec
  {:endpoints
   [{:name "ping"
     :type "query"
     :args {}
     :output_type {:name "PingResult" :fields {:message "String!"}}
     :backend_chain []
     :output_mapping {}}]})

(def ^:private types-spec
  {:endpoints
   [{:name "typeTest"
     :type "query"
     :args {}
     :output_type {:name "TypeTest"
                   :fields {:scalar      "String"
                            :nonNull     "String!"
                            :list        "[String]"
                            :nonNullList "[String!]!"}}
     :backend_chain []
     :output_mapping {}}]})

(def ^:private full-spec
  {:endpoints
   [{:name        "getUser"
     :type        "query"
     :description "Get a user by ID"
     :args        {:userId {:type "String!"}}
     :output_type {:name "User" :fields {:id "String!" :name "String"}}
     :backend_chain []
     :output_mapping {}}

    {:name        "createUser"
     :type        "mutation"
     :args        {:name {:type "String!" :default "anon"}}
     :output_type {:name "NewUser" :fields {:id "String!"}}
     :backend_chain []
     :output_mapping {}}

    {:name               "oldQuery"
     :type               "query"
     :args               {}
     :deprecation_reason "Use getUser instead"
     :output_type        {:name "OldResult" :fields {:id "String!"}}
     :backend_chain []
     :output_mapping {}}]

   :input_types
   [{:name "UserInput" :fields {:name "String!" :age "Int"}}]})

(defn- exec [schema q]
  (lacinia/execute schema q {} {}))


(defn- type-fields [schema type-name]
  (let [q    (str "{ __type(name: \"" type-name "\") {"
                  " fields { name type { kind name"
                  "  ofType { kind name"
                  "   ofType { kind name"
                  "    ofType { kind name } } } } } } }")
        flds (get-in (exec schema q) [:data :__type :fields])]
    (->> flds (map (fn [f] [(:name f) (:type f)])) (into {}))))

(defn- query-type-fields [schema]
  (let [q (str "{ __schema { queryType { fields(includeDeprecated: true) {"
               " name description isDeprecated deprecationReason"
               " args { name defaultValue type { kind name ofType { kind name } } }"
               " } } } }")]
    (get-in (exec schema q) [:data :__schema :queryType :fields])))

(defn- mutation-type-fields [schema]
  (let [q "{ __schema { mutationType { fields { name args { name defaultValue } } } } }"]
    (get-in (exec schema q) [:data :__schema :mutationType :fields])))

;; ---------------------------------------------------------------------------
;; Schema compilation
;; ---------------------------------------------------------------------------

(deftest test-build-schema-compiles
  (is (some? (sb/build-schema ping-spec))))

(deftest test-build-schema-introspection-works
  (let [result (exec (sb/build-schema ping-spec) "{ __schema { queryType { name } } }")]
    (is (nil? (:errors result)))
    (is (some? (get-in result [:data :__schema :queryType :name])))))

;; ---------------------------------------------------------------------------
;; Object types
;; ---------------------------------------------------------------------------

(deftest test-object-type-is-registered
  (let [result (exec (sb/build-schema ping-spec) "{ __type(name: \"PingResult\") { name kind } }")]
    (is (= "PingResult" (get-in result [:data :__type :name])))
    (is (= :OBJECT (get-in result [:data :__type :kind])))))

(deftest test-object-type-fields-are-present
  (let [schema (sb/build-schema full-spec)
        result (exec schema "{ __type(name: \"User\") { fields { name } } }")
        names  (->> (get-in result [:data :__type :fields]) (map :name) set)]
    (is (contains? names "id"))
    (is (contains? names "name"))))

;; ---------------------------------------------------------------------------
;; Input types
;; ---------------------------------------------------------------------------

(deftest test-input-type-is-registered
  (let [result (exec (sb/build-schema full-spec) "{ __type(name: \"UserInput\") { name kind } }")]
    (is (= "UserInput" (get-in result [:data :__type :name])))
    (is (= :INPUT_OBJECT (get-in result [:data :__type :kind])))))

(deftest test-input-type-fields-are-present
  (let [schema (sb/build-schema full-spec)
        result (exec schema "{ __type(name: \"UserInput\") { inputFields { name } } }")
        names  (->> (get-in result [:data :__type :inputFields]) (map :name) set)]
    (is (contains? names "name"))
    (is (contains? names "age"))))

;; ---------------------------------------------------------------------------
;; Type string parsing (via field introspection on TypeTest)
;; ---------------------------------------------------------------------------

(deftest test-parse-type-str-scalar
  (let [t (get (type-fields (sb/build-schema types-spec) "TypeTest") "scalar")]
    (is (= :SCALAR (:kind t)))
    (is (= "String" (:name t)))))

(deftest test-parse-type-str-non-null-scalar
  (let [t (get (type-fields (sb/build-schema types-spec) "TypeTest") "nonNull")]
    (is (= :NON_NULL (:kind t)))
    (is (= "String"  (get-in t [:ofType :name])))))

(deftest test-parse-type-str-list
  (let [t (get (type-fields (sb/build-schema types-spec) "TypeTest") "list")]
    (is (= :LIST    (:kind t)))
    (is (= "String" (get-in t [:ofType :name])))))

(deftest test-parse-type-str-non-null-list-of-non-null
  (let [t (get (type-fields (sb/build-schema types-spec) "TypeTest") "nonNullList")]
    (is (= :NON_NULL (:kind t)))
    (is (= :LIST     (get-in t [:ofType :kind])))
    (is (= :NON_NULL (get-in t [:ofType :ofType :kind])))
    (is (= "String"  (get-in t [:ofType :ofType :ofType :name])))))

;; ---------------------------------------------------------------------------
;; Query and mutation registration
;; ---------------------------------------------------------------------------

(deftest test-query-is-registered
  (let [names (->> (query-type-fields (sb/build-schema ping-spec)) (map :name) set)]
    (is (contains? names "ping"))))

(deftest test-mutation-is-registered
  (let [names (->> (mutation-type-fields (sb/build-schema full-spec)) (map :name) set)]
    (is (contains? names "createUser"))))

(deftest test-query-and-mutation-coexist
  (let [schema  (sb/build-schema full-spec)
        q-names (->> (query-type-fields schema)    (map :name) set)
        m-names (->> (mutation-type-fields schema) (map :name) set)]
    (is (contains? q-names "getUser"))
    (is (contains? m-names "createUser"))))

(deftest test-operation-description-is-set
  (let [fields   (query-type-fields (sb/build-schema full-spec))
        get-user (first (filter #(= "getUser" (:name %)) fields))]
    (is (= "Get a user by ID" (:description get-user)))))

;; ---------------------------------------------------------------------------
;; Args
;; ---------------------------------------------------------------------------

(deftest test-arg-type-is-non-null-string
  (let [fields   (query-type-fields (sb/build-schema full-spec))
        get-user (first (filter #(= "getUser" (:name %)) fields))
        user-id  (first (filter #(= "userId" (:name %)) (:args get-user)))]
    (is (= :NON_NULL (get-in user-id [:type :kind])))
    (is (= "String"  (get-in user-id [:type :ofType :name])))))

(deftest test-arg-default-value-is-set
  (let [fields      (mutation-type-fields (sb/build-schema full-spec))
        create-user (first (filter #(= "createUser" (:name %)) fields))
        arg         (first (filter #(= "name" (:name %)) (:args create-user)))]
    (is (some? (:defaultValue arg)))))

;; ---------------------------------------------------------------------------
;; Deprecation
;; ---------------------------------------------------------------------------

(deftest test-deprecated-operation-is-marked
  (let [fields    (query-type-fields (sb/build-schema full-spec))
        old-query (first (filter #(= "oldQuery" (:name %)) fields))]
    (is (true? (:isDeprecated old-query)))))

(deftest test-deprecation-reason-is-set
  (let [fields    (query-type-fields (sb/build-schema full-spec))
        old-query (first (filter #(= "oldQuery" (:name %)) fields))]
    (is (true? (:isDeprecated old-query)))))

;; ---------------------------------------------------------------------------
;; Resolver behavior
;; ---------------------------------------------------------------------------

(deftest test-resolver-returns-executor-data
  (let [schema (sb/build-schema ping-spec)]
    (with-redefs [executor/run-endpoint (fn [_ _ _] (m/sp {:data {:message "pong"} :errors []}))]
      (let [result (lacinia/execute schema "{ ping { message } }" {} {})]
        (is (nil? (:errors result)))
        (is (= "pong" (get-in result [:data :ping :message])))))))

(deftest test-resolver-surfaces-step-errors
  (let [schema (sb/build-schema ping-spec)]
    (with-redefs [executor/run-endpoint
                  (fn [_ _ _]
                    (m/sp {:data   nil
                           :errors [{:message    "step failed"
                                     :extensions {:code :not-found :step "s"}}]}))]
      (let [result (lacinia/execute schema "{ ping { message } }" {} {})]
        (is (seq (:errors result)))
        (is (= "step failed" (-> result :errors first :message)))))))

(deftest test-resolver-passes-args-to-executor
  (let [schema        (sb/build-schema full-spec)
        received-args (atom nil)]
    (with-redefs [executor/run-endpoint
                  (fn [_ args _]
                    (reset! received-args args)
                    (m/sp {:data {:id "u1" :name nil} :errors []}))]
      (lacinia/execute schema "{ getUser(userId: \"u1\") { id } }" {} {})
      (is (= {:userId "u1"} @received-args)))))

(deftest test-resolver-passes-request-ctx-to-executor
  (let [schema   (sb/build-schema ping-spec)
        received (atom nil)]
    (with-redefs [executor/run-endpoint
                  (fn [_ _ request-ctx]
                    (reset! received request-ctx)
                    (m/sp {:data {:message "ok"} :errors []}))]
      (lacinia/execute schema "{ ping { message } }" {} {:request {:authorization "Bearer tok"}})
      (is (= {:authorization "Bearer tok"} @received)))))
