(ns bff.linter-test
  (:require [clojure.test :refer [deftest is]]
            [bff.linter :as linter]))

(deftest test-empty-spec-produces-no-problems
  (is (= [] (linter/lint-spec {}))))

(deftest test-problem-shape-round-trips
  (is (linter/problem? {:severity :error
                        :path     "endpoints[0]"
                        :message  "missing :name"
                        :node     {:type "query"}}))
  (is (not (linter/problem? {:severity :error :path "x" :message "y"})))
  (is (not (linter/problem? {:severity :bogus :path "x" :message "y" :node {}}))))
