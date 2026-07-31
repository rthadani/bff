(ns bff.cache-test
  (:require [clojure.test :refer [deftest is testing]]
            [bff.cache :as cache]))

(deftest test-clojure-protocol-impl-used-when-passed
  (let [state (atom {})
        store (reify cache/CacheStore
                (cache-get        [_ k]     (get @state k))
                (cache-put        [_ k v _] (swap! state assoc k v))
                (cache-invalidate [_ k]     (swap! state dissoc k)))]
    (cache/save store "k1" "v1" 60000)
    (is (= "v1" (cache/lookup store "k1")))
    (cache/invalidate store "k1")
    (is (nil? (cache/lookup store "k1")))))

(deftest test-java-interface-impl-used-when-passed
  (testing "a class implementing io.github.rthadani.bff.CacheStore works via extend-type"
    (let [state (atom {})
          store (reify io.github.rthadani.bff.CacheStore
                  (get        [_ k]     (@state k))
                  (put        [_ k v _] (swap! state assoc k v))
                  (invalidate [_ k]     (swap! state dissoc k)))]
      (cache/save store "k2" "v2" 60000)
      (is (= "v2" (cache/lookup store "k2")))
      (cache/invalidate store "k2")
      (is (nil? (cache/lookup store "k2"))))))

(deftest test-lookup-swallows-exceptions
  (let [store (reify cache/CacheStore
                (cache-get [_ _]         (throw (RuntimeException. "boom")))
                (cache-put [_ _ _ _]     nil)
                (cache-invalidate [_ _]  nil))]
    (is (nil? (cache/lookup store "any")))))

(deftest test-nil-store-is-noop
  (is (nil? (cache/lookup nil "anything")))
  (is (nil? (cache/save nil "k" "v" 1000)))
  (is (nil? (cache/invalidate nil "k"))))
