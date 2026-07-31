(ns bff.facade-test
  (:require [clojure.test :refer [deftest is testing]])
  (:import [io.github.rthadani.bff
            Bff BffConfig
            BffContextEnricher BffValidator BffTransformer BffResolver BffRetryHook
            CacheStore]))

;; ---------------------------------------------------------------------------
;; BffConfig builder
;; ---------------------------------------------------------------------------

(deftest test-builder-produces-empty-config-when-no-registrations
  (let [c (.build (BffConfig/builder))]
    (is (empty? (.enrichers c)))
    (is (empty? (.validators c)))
    (is (empty? (.transformers c)))
    (is (empty? (.resolvers c)))
    (is (empty? (.retryHooks c)))
    (is (nil?   (.cache c)))))

(deftest test-builder-accumulates-enrichers-in-order
  (let [e1 (reify BffContextEnricher (enrich [_ _] nil))
        e2 (reify BffContextEnricher (enrich [_ _] nil))
        c  (-> (BffConfig/builder) (.enricher e1) (.enricher e2) .build)]
    (is (= [e1 e2] (vec (.enrichers c))))))

(deftest test-builder-keys-validators-by-string
  (let [v (reify BffValidator (validate [_ _ _] nil))
        c (-> (BffConfig/builder) (.validator "k" v) .build)]
    (is (= v (get (.validators c) "k")))))

(deftest test-builder-keys-transformers-resolvers-retry-hooks
  (let [t  (reify BffTransformer (transform [_ _ _ m] m))
        r  (reify BffResolver    (resolve   [_ _ _] nil))
        h  (reify BffRetryHook   (beforeRetry [_ _] nil))
        c  (-> (BffConfig/builder)
               (.transformer "t" t)
               (.resolver    "r" r)
               (.retryHook   "h" h)
               .build)]
    (is (= t (get (.transformers c) "t")))
    (is (= r (get (.resolvers    c) "r")))
    (is (= h (get (.retryHooks   c) "h")))))

(deftest test-builder-sets-cache
  (let [store (reify CacheStore
                (get [_ _] nil) (put [_ _ _ _] nil) (invalidate [_ _] nil))
        c (-> (BffConfig/builder) (.cache store) .build)]
    (is (= store (.cache c)))))

(deftest test-builder-produces-immutable-collections
  (let [c (-> (BffConfig/builder)
              (.validator "k" (reify BffValidator (validate [_ _ _] nil)))
              .build)]
    (is (thrown? UnsupportedOperationException
                 (.put (.validators c) "extra" nil)))))

;; ---------------------------------------------------------------------------
;; Bff.createHandler / createServlet
;; ---------------------------------------------------------------------------

(deftest test-create-handler-no-args-uses-empty-config
  (let [h (Bff/createHandler "spec-loader-fixture.yaml")]
    (is (instance? clojure.lang.IFn h))))

(deftest test-create-handler-with-config
  (let [config (-> (BffConfig/builder)
                   (.validator "v" (reify BffValidator (validate [_ _ _] nil)))
                   .build)
        h (Bff/createHandler "spec-loader-fixture.yaml" config)]
    (is (instance? clojure.lang.IFn h))))

(deftest test-create-servlet-wraps-handler
  (let [servlet (Bff/createServlet "spec-loader-fixture.yaml")]
    (is (instance? jakarta.servlet.http.HttpServlet servlet))
    (is (instance? io.github.rthadani.bff.BffServlet servlet))))

(deftest test-create-servlet-with-config
  (let [config  (.build (BffConfig/builder))
        servlet (Bff/createServlet "spec-loader-fixture.yaml" config)]
    (is (instance? io.github.rthadani.bff.BffServlet servlet))))
