(ns bff.interop-test
  (:require [clojure.test :refer [deftest is testing]]
            [bff.interop :as interop])
  (:import [java.util LinkedHashMap ArrayList]))

(deftest test-clj-map-to-java-string-keys
  (let [j (interop/->java {:userId "u1" :amount 42})]
    (is (instance? java.util.Map j))
    (is (= "u1" (.get ^java.util.Map j "userId")))
    (is (= 42  (.get ^java.util.Map j "amount")))))

(deftest test-nested-clj-map-recursively-converted
  (let [j (interop/->java {:profile {:name "Bob" :city "NY"}})]
    (is (= "Bob" (-> ^java.util.Map j (.get "profile") ^java.util.Map (.get "name"))))))

(deftest test-clj-vector-becomes-list
  (let [j (interop/->java {:items ["a" "b"]})]
    (is (instance? java.util.List (.get ^java.util.Map j "items")))))

(deftest test-keyword-value-stringified
  (testing "keywords in values become strings so Java code doesn't see clojure.lang.Keyword"
    (let [j (interop/->java {:status :ok})]
      (is (= "ok" (.get ^java.util.Map j "status"))))))

(deftest test-java-map-to-clj-keywordizes-keys
  (let [m (doto (LinkedHashMap.)
            (.put "userId" "u1")
            (.put "amount" 42))]
    (is (= {:userId "u1" :amount 42} (interop/->clj m)))))

(deftest test-nested-java-map-recursively-converted
  (let [inner (doto (LinkedHashMap.) (.put "name" "Bob"))
        outer (doto (LinkedHashMap.) (.put "profile" inner))]
    (is (= {:profile {:name "Bob"}} (interop/->clj outer)))))

(deftest test-java-list-becomes-vector
  (let [l (doto (ArrayList.) (.add "a") (.add "b"))
        m (doto (LinkedHashMap.) (.put "items" l))]
    (is (= {:items ["a" "b"]} (interop/->clj m)))))

(deftest test-idempotent-on-clj-data
  (is (= {:a 1} (interop/->clj {:a 1})))
  (is (= [1 2] (interop/->clj [1 2]))))
