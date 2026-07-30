(ns bff.facade-test
  (:require [clojure.test :refer [deftest is testing]]
            [bff.validator :as validator]
            [bff.executor :as executor]
            [bff.cache :as cache])
  (:import [io.github.rthadani.bff Bff BffValidator BffTransformer BffResolver CacheStore]))

;; ---------------------------------------------------------------------------
;; Bff.register* delegates into the underlying Clojure registries
;; ---------------------------------------------------------------------------

(deftest test-register-validator-reaches-registry
  (let [impl (reify BffValidator
               (validate [_ _ _]
                 (java.util.List/of (doto (java.util.HashMap.)
                                      (.put "message" "from facade")))))]
    (Bff/registerValidator "facade-validator" impl)
    (let [errors (validator/run-validation
                   {:validator {:key "facade-validator"}}
                   {} {})]
      (is (= 1 (count errors)))
      (is (= "from facade" (:message (first errors)))))))

(deftest test-register-transformer-reaches-registry
  (let [impl (reify BffTransformer
               (transform [_ _ _ _]
                 (doto (java.util.HashMap.) (.put "flag" true))))]
    (Bff/registerTransformer "facade-transformer" impl)
    (let [f (#'executor/resolve-transformer {:key "facade-transformer"})]
      (is (some? f)))))

(deftest test-register-resolver-reaches-registry
  (let [impl (reify BffResolver
               (resolve [_ _ _]
                 (doto (java.util.HashMap.)
                   (.put "data"   (doto (java.util.HashMap.) (.put "ok" true)))
                   (.put "errors" (java.util.List/of)))))]
    (Bff/registerResolver "facade-resolver" impl)
    (let [r (#'executor/resolve-resolver {:key "facade-resolver"})]
      (is (some? r)))))

(deftest test-register-cache-reaches-store
  (let [store (atom {})
        impl  (reify CacheStore
                (get        [_ k]     (@store k))
                (put        [_ k v _] (swap! store assoc k v))
                (invalidate [_ k]     (swap! store dissoc k)))]
    (Bff/registerCache impl)
    (cache/save "facade-key" "value" 60000)
    (is (= "value" (cache/lookup "facade-key")))
    (cache/register-cache! nil)))

(deftest test-register-cache-nil-disables
  (testing "passing null clears the registered store"
    (Bff/registerCache nil)
    (is (nil? (cache/lookup "any-key")))))

;; ---------------------------------------------------------------------------
;; Bff.createHandler / createServlet
;; ---------------------------------------------------------------------------

;; A minimal spec fixture for the create* smoke tests lives under
;; test/multi-spec/. Reuse users.yaml which is already present.

(deftest test-create-handler-returns-callable
  (let [h (Bff/createHandler "spec-loader-fixture.yaml")]
    (is (some? h))
    (is (instance? clojure.lang.IFn h))))

(deftest test-create-servlet-wraps-handler
  (let [servlet (Bff/createServlet "spec-loader-fixture.yaml")]
    (is (instance? jakarta.servlet.http.HttpServlet servlet))
    (is (instance? io.github.rthadani.bff.BffServlet servlet))))
