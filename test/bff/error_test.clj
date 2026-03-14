(ns bff.error-test
  (:require [clojure.test :refer [deftest is testing]]
            [bff.error :as error]
            [bff.http-client :as http]))

(deftest test-step-errors-empty
  (testing "No errors in all-ok chain-ctx"
    (let [ctx {:step_a (http/ok {:id 1})
               :step_b (http/ok {:data "x"})}]
      (is (empty? (error/step-errors ctx))))))

(deftest test-step-errors-partial
  (testing "Mixed ok/error chain-ctx surfaces errors"
    (let [ctx {:step_a (http/ok {:id 1})
               :step_b (http/err :timeout "Request timed out")}
          errs (error/step-errors ctx)]
      (is (= 1 (count errs)))
      (is (= :timeout (get-in (first errs) [:extensions :code])))
      (is (= "step_b" (get-in (first errs) [:extensions :step]))))))

(deftest test-safe-data
  (testing "safe-data returns nil for errors"
    (is (nil? (error/safe-data (http/err :not-found "404"))))
    (is (= {:x 1} (error/safe-data (http/ok {:x 1}))))))

(deftest test-critical-step-throws
  (testing "throw-if-critical! throws when critical step fails"
    (let [ctx {:create_order (http/err :backend-error "500")}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (error/throw-if-critical! ctx ["create_order"]))))))
