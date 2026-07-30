(ns bff.cache-test
  (:require [clojure.test :refer [deftest is testing]]
            [bff.cache :as cache]))

(deftest test-clojure-protocol-impl-registered
  (let [store (atom {})
        impl  (reify cache/CacheStore
                (cache-get        [_ k]     (get @store k))
                (cache-put        [_ k v _] (swap! store assoc k v))
                (cache-invalidate [_ k]     (swap! store dissoc k)))]
    (cache/register-cache! impl)
    (cache/save "k1" "v1" 60000)
    (is (= "v1" (cache/lookup "k1")))
    (cache/invalidate "k1")
    (is (nil? (cache/lookup "k1")))
    (cache/register-cache! nil)))

(deftest test-java-interface-impl-registered
  (testing "a class implementing io.github.rthadani.bff.CacheStore works via extend-type"
    (let [store (atom {})
          impl  (reify io.github.rthadani.bff.CacheStore
                  (get        [_ k]     (@store k))
                  (put        [_ k v _] (swap! store assoc k v))
                  (invalidate [_ k]     (swap! store dissoc k)))]
      (cache/register-cache! impl)
      (cache/save "k2" "v2" 60000)
      (is (= "v2" (cache/lookup "k2")))
      (cache/invalidate "k2")
      (is (nil? (cache/lookup "k2")))
      (cache/register-cache! nil))))

(deftest test-lookup-swallows-exceptions
  (cache/register-cache!
    (reify cache/CacheStore
      (cache-get [_ _]         (throw (RuntimeException. "boom")))
      (cache-put [_ _ _ _]     nil)
      (cache-invalidate [_ _]  nil)))
  (is (nil? (cache/lookup "any")))
  (cache/register-cache! nil))

(deftest test-no-cache-registered-returns-nil
  (cache/register-cache! nil)
  (is (nil? (cache/lookup "anything"))))
