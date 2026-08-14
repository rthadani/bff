(ns bff.jq-on-args-test
  (:require [clojure.test :refer [deftest is testing]]
            [bff.executor :as executor]
            [bff.http-client :as http]
            [bff.jq-engine :as jq]))

(defn- run-sync! [task]
  (let [p (promise)]
    (task #(deliver p [:ok %]) #(deliver p [:err %]))
    (let [[tag v] @p]
      (if (= :err tag) (throw v) v))))

(defn- with-jq [mapping expr]
  (assoc mapping :jq expr :compiled-jq (jq/compile-query expr)))

(def ^:private base-step
  {:id "s" :url "http://api" :method "POST" :deps []})

(deftest test-args-with-jq-transforms-scalar
  (let [captured (atom nil)
        step (assoc base-step
                    :body_mapping
                    {:fromTime (with-jq {:source "args" :key "days"}
                                        "now - . * 86400 | floor")})]
    (with-redefs [http/call (fn [{:keys [body]}]
                              (reset! captured body)
                              (http/ok {}))]
      (run-sync! (executor/execute-graph [step] {:days 5} {} {}))
      (is (number? (:fromTime @captured)))
      (is (< (:fromTime @captured) (quot (System/currentTimeMillis) 1000))))))

(deftest test-args-without-jq-returns-raw
  (let [captured (atom nil)
        step (assoc base-step
                    :body_mapping
                    {:days {:source "args" :key "days"}})]
    (with-redefs [http/call (fn [{:keys [body]}]
                              (reset! captured body)
                              (http/ok {}))]
      (run-sync! (executor/execute-graph [step] {:days 5} {} {}))
      (is (= 5 (:days @captured))))))

(deftest test-ctx-with-jq-transforms
  (let [captured (atom nil)
        step (assoc base-step
                    :body_mapping
                    {:tenant (with-jq {:source "ctx" :key "authorization"}
                                      ". | split(\" \") | .[1]")})]
    (with-redefs [http/call (fn [{:keys [body]}]
                              (reset! captured body)
                              (http/ok {}))]
      (run-sync! (executor/execute-graph [step] {} {:authorization "Bearer abc123"} {}))
      (is (= "abc123" (:tenant @captured))))))

(deftest test-args-jq-nil-arg-returns-default
  (testing "jq on a missing arg still runs on nil and can supply a default"
    (let [captured (atom nil)
          step (assoc base-step
                      :body_mapping
                      {:x (with-jq {:source "args" :key "missing"} ". // \"default\"")})]
      (with-redefs [http/call (fn [{:keys [body]}]
                                (reset! captured body)
                                (http/ok {}))]
        (run-sync! (executor/execute-graph [step] {} {} {}))
        (is (= "default" (:x @captured)))))))

(deftest test-args-jq-object-arg
  (testing "jq on an input-object arg can pluck a field"
    (let [captured (atom nil)
          step (assoc base-step
                      :body_mapping
                      {:sku (with-jq {:source "args" :key "order"} ".sku")})]
      (with-redefs [http/call (fn [{:keys [body]}]
                                (reset! captured body)
                                (http/ok {}))]
        (run-sync! (executor/execute-graph
                     [step] {:order {:sku "SKU-1" :qty 2}} {} {}))
        (is (= "SKU-1" (:sku @captured)))))))
