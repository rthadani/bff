(ns bff.sdl-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [bff.sdl :as sdl]
            [bff.schema-builder :as sb]))

(deftest test-scalar-type-emission
  (let [s (sdl/emit-sdl {:scalars {:Mac {:description "MAC address"}}})]
    (is (str/includes? s "scalar Mac"))
    (is (str/includes? s "MAC address"))))

(deftest test-object-type-with-non-null-fields
  (let [m {:objects {:User {:fields {:id   {:type '(non-null String)}
                                     :name {:type 'String}}}}}]
    (is (str/includes? (sdl/emit-sdl m) "type User {"))
    (is (str/includes? (sdl/emit-sdl m) "id: String!"))
    (is (str/includes? (sdl/emit-sdl m) "name: String"))))

(deftest test-list-and-nested-non-null-types
  (let [m {:objects {:List {:fields {:tags {:type '(non-null (list (non-null String)))}}}}}]
    (is (str/includes? (sdl/emit-sdl m) "tags: [String!]!"))))

(deftest test-input-type-block
  (let [m {:input-objects {:CreateUser {:fields {:email {:type '(non-null String)}}}}}]
    (is (str/includes? (sdl/emit-sdl m) "input CreateUser {"))
    (is (str/includes? (sdl/emit-sdl m) "email: String!"))))

(deftest test-query-block-emits-args
  (let [m {:queries {:user {:type      :User
                            :args      {:id {:type '(non-null ID)}}
                            :resolve   (fn [_ _ _] nil)}}}]
    (is (str/includes? (sdl/emit-sdl m) "type Query {"))
    (is (str/includes? (sdl/emit-sdl m) "user(id: ID!): User"))))

(deftest test-arg-default-value-renders
  (let [m {:queries {:list {:type 'String
                            :args {:limit {:type 'Int :default-value 10}}}}}]
    (is (str/includes? (sdl/emit-sdl m) "limit: Int = 10"))))

(deftest test-empty-schema-produces-empty-string
  (is (= "" (sdl/emit-sdl {}))))

(deftest test-round-trip-with-schema-builder
  (testing "SDL for a small spec covers the essential blocks"
    (let [spec {:endpoints
                [{:name        "hello"
                  :type        "query"
                  :description "Say hi"
                  :output_type {:name   "Hello"
                                :fields {:message "String!"}}
                  :args        {:name {:type "String!"}}}]}
          m    (sb/build-schema-map spec)
          txt  (sdl/emit-sdl m)]
      (is (str/includes? txt "type Hello {"))
      (is (str/includes? txt "message: String!"))
      (is (str/includes? txt "type Query {"))
      (is (str/includes? txt "hello(name: String!): Hello")))))
