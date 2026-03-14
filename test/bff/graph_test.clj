(ns bff.graph-test
  (:require [clojure.test :refer [deftest is testing]]
            [bff.graph :as graph]))

(deftest test-execution-waves-no-deps
  (testing "All steps with no deps → single wave (all parallel)"
    (let [chain [{:id "a" :deps []}
                 {:id "b" :deps []}
                 {:id "c" :deps []}]
          waves (graph/execution-waves chain)]
      (is (= 1 (count waves)))
      (is (= #{"a" "b" "c"}
             (set (map :id (first waves))))))))

(deftest test-execution-waves-linear-chain
  (testing "Linear chain a→b→c → three waves of one each"
    (let [chain [{:id "a" :deps []}
                 {:id "b" :deps ["a"]}
                 {:id "c" :deps ["b"]}]
          waves (graph/execution-waves chain)]
      (is (= 3 (count waves)))
      (is (= ["a"] (map :id (nth waves 0))))
      (is (= ["b"] (map :id (nth waves 1))))
      (is (= ["c"] (map :id (nth waves 2)))))))

(deftest test-execution-waves-diamond
  (testing "Diamond: a → (b,c) → d"
    (let [chain [{:id "a" :deps []}
                 {:id "b" :deps ["a"]}
                 {:id "c" :deps ["a"]}
                 {:id "d" :deps ["b" "c"]}]
          waves (graph/execution-waves chain)]
      (is (= 3 (count waves)))
      (is (= ["a"] (map :id (nth waves 0))))
      (is (= #{"b" "c"} (set (map :id (nth waves 1)))))
      (is (= ["d"] (map :id (nth waves 2)))))))

(deftest test-circular-dep-throws
  (testing "Circular dependency detected"
    (let [chain [{:id "a" :deps ["b"]}
                 {:id "b" :deps ["a"]}]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (graph/execution-waves chain))))))

(deftest test-unknown-dep-throws
  (testing "Reference to non-existent step throws"
    (let [chain [{:id "a" :deps ["nonexistent"]}]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (graph/execution-waves chain))))))

