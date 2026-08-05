(ns bff.cache-invalidate-test
  (:require [clojure.test :refer [deftest is testing]]
            [bff.cache :as cache]
            [bff.executor :as executor]
            [bff.http-client :as http]))

(defn- run-sync! [task]
  (let [p (promise)]
    (task #(deliver p [:ok %]) #(deliver p [:err %]))
    (let [[tag v] @p]
      (if (= :err tag) (throw v) v))))

(defn- fake-store [state]
  (reify cache/CacheStore
    (cache-get        [_ k]     (@state k))
    (cache-put        [_ k v _] (swap! state assoc k v))
    (cache-invalidate [_ k]     (swap! state dissoc k))))

(defn- graph [chain exts]
  (run-sync! (executor/execute-graph chain {} {} exts)))

(def ^:private base-step
  {:id "s" :url "http://api" :method "POST" :deps []})

(deftest test-successful-step-invalidates-listed-keys
  (let [state (atom {"profile:42" "cached" "other" "kept"})
        step  (assoc base-step :cache_invalidate ["profile:42"])]
    (with-redefs [http/call (fn [_] (http/ok {}))]
      (graph [step] {:cache (fake-store state)})
      (is (nil? (@state "profile:42")))
      (is (= "kept" (@state "other"))))))

(deftest test-failed-step-does-not-invalidate
  (let [state (atom {"profile:42" "cached"})
        step  (assoc base-step :cache_invalidate ["profile:42"])]
    (with-redefs [http/call (fn [_] (http/err :backend-error "500"))]
      (graph [step] {:cache (fake-store state)})
      (is (= "cached" (@state "profile:42"))))))

(deftest test-invalidate-with-arg-interpolation
  (let [state (atom {"profile:u1" "x" "profile:u2" "y"})
        step  (assoc base-step :cache_invalidate ["profile:{userId}"])]
    (with-redefs [http/call (fn [_] (http/ok {}))]
      (run-sync! (executor/execute-graph [step] {:userId "u1"} {} {:cache (fake-store state)}))
      (is (nil? (@state "profile:u1")))
      (is (= "y" (@state "profile:u2"))))))

(deftest test-invalidate-with-step-result-interpolation
  (let [state (atom {"profile:new-id" "cached"})
        step  (assoc base-step :cache_invalidate ["profile:{id}"])]
    (with-redefs [http/call (fn [_] (http/ok {:id "new-id"}))]
      (graph [step] {:cache (fake-store state)})
      (is (nil? (@state "profile:new-id"))))))

(deftest test-no-cache-registered-is-noop
  (testing "step declares invalidate but no cache store configured"
    (let [step (assoc base-step :cache_invalidate ["anything"])]
      (with-redefs [http/call (fn [_] (http/ok {}))]
        (is (some? (graph [step] {})))))))

(deftest test-multiple-keys-all-invalidated
  (let [state (atom {"a" 1 "b" 2 "c" 3})
        step  (assoc base-step :cache_invalidate ["a" "b"])]
    (with-redefs [http/call (fn [_] (http/ok {}))]
      (graph [step] {:cache (fake-store state)})
      (is (= {"c" 3} @state)))))
