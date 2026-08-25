(ns bff.compensation-test
  "Tests for step-level compensation — cascading rollback of successful
   steps when a later critical step fails."
  (:require [clojure.test :refer [deftest is testing]]
            [bff.executor :as executor]
            [bff.http-client :as http]))

(defn- run-sync! [task]
  (let [p (java.util.concurrent.CompletableFuture.)]
    (task #(.complete p %) #(.completeExceptionally p %))
    (try (.get p)
         (catch java.util.concurrent.ExecutionException e
           (throw (.getCause e))))))

(defn- graph [chain] (executor/execute-graph chain {} {} {}))

;; ---------------------------------------------------------------------------
;; Chain success — compensations never run
;; ---------------------------------------------------------------------------

(deftest test-compensations-not-run-when-chain-succeeds
  (let [calls (atom [])]
    (with-redefs [http/call (fn [_ {:keys [url]}]
                              (swap! calls conj url)
                              (http/ok {}))]
      (run-sync!
        (graph [{:id "a" :url "http://a" :method "GET" :deps []
                 :critical true
                 :compensation {:url "http://undo-a" :method "DELETE"}}
                {:id "b" :url "http://b" :method "GET" :deps ["a"]
                 :critical true}]))
      (is (= ["http://a" "http://b"] @calls)
          "compensation URL must not appear when both steps succeeded"))))

;; ---------------------------------------------------------------------------
;; Chain failure — compensation runs for the completed step
;; ---------------------------------------------------------------------------

(deftest test-compensation-runs-when-later-critical-step-fails
  (let [calls (atom [])]
    (with-redefs [http/call (fn [_ {:keys [url method]}]
                              (swap! calls conj [(name method) url])
                              (if (= "http://b" url)
                                (http/err :backend-error "500")
                                (http/ok {})))]
      (try
        (run-sync!
          (graph [{:id "a" :url "http://a" :method "GET" :deps []
                   :compensation {:url "http://undo-a" :method "DELETE"}}
                  {:id "b" :url "http://b" :method "GET" :deps ["a"]
                   :critical true}]))
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo _))
      (is (= [["get" "http://a"] ["get" "http://b"] ["delete" "http://undo-a"]]
             @calls)))))

(deftest test-compensations-run-in-reverse-order
  (let [calls (atom [])]
    (with-redefs [http/call (fn [_ {:keys [url method]}]
                              (swap! calls conj [(name method) url])
                              (if (= "http://d" url)
                                (http/err :backend-error "500")
                                (http/ok {})))]
      (try
        (run-sync!
          (graph [{:id "a" :url "http://a" :method "GET" :deps []
                   :compensation {:url "http://undo-a" :method "DELETE"}}
                  {:id "b" :url "http://b" :method "GET" :deps ["a"]
                   :compensation {:url "http://undo-b" :method "DELETE"}}
                  {:id "c" :url "http://c" :method "GET" :deps ["b"]
                   :compensation {:url "http://undo-c" :method "DELETE"}}
                  {:id "d" :url "http://d" :method "GET" :deps ["c"]
                   :critical true}]))
        (catch clojure.lang.ExceptionInfo _))
      (is (= [["get" "http://a"] ["get" "http://b"] ["get" "http://c"] ["get" "http://d"]
              ["delete" "http://undo-c"] ["delete" "http://undo-b"] ["delete" "http://undo-a"]]
             @calls)
          "compensations run c → b → a, mirroring successful step order"))))

;; ---------------------------------------------------------------------------
;; Failed step: nothing to compensate for
;; ---------------------------------------------------------------------------

(deftest test-failed-step-has-no-compensation-recorded
  (let [calls (atom [])]
    (with-redefs [http/call (fn [_ {:keys [url method]}]
                              (swap! calls conj [(name method) url])
                              (if (= "http://b" url)
                                (http/err :backend-error "500")
                                (http/ok {})))]
      (try
        (run-sync!
          (graph [{:id "a" :url "http://a" :method "GET" :deps []
                   :compensation {:url "http://undo-a" :method "DELETE"}}
                  {:id "b" :url "http://b" :method "GET" :deps ["a"]
                   :critical true
                   :compensation {:url "http://undo-b" :method "DELETE"}}]))
        (catch clojure.lang.ExceptionInfo _))
      (is (some #(= ["delete" "http://undo-a"] %) @calls)
          "a's compensation runs — a succeeded")
      (is (not-any? #(= ["delete" "http://undo-b"] %) @calls)
          "b's compensation must NOT run — b never succeeded"))))

;; ---------------------------------------------------------------------------
;; Compensation errors are swallowed
;; ---------------------------------------------------------------------------

(deftest test-compensation-failure-is-logged-not-thrown
  (let [calls (atom [])]
    (with-redefs [http/call (fn [_ {:keys [url method]}]
                              (swap! calls conj [(name method) url])
                              (cond
                                (= url "http://b")       (http/err :backend-error "500")
                                (= url "http://undo-a")  (http/err :backend-error "500")
                                :else                    (http/ok {})))]
      (try
        (run-sync!
          (graph [{:id "a" :url "http://a" :method "GET" :deps []
                   :compensation {:url "http://undo-a" :method "DELETE"}}
                  {:id "b" :url "http://b" :method "GET" :deps ["a"]
                   :critical true}]))
        (is false "should have thrown original chain failure")
        (catch clojure.lang.ExceptionInfo e
          (is (= "b" (:step (ex-data e)))
              "the exception surfaced is the original critical-step failure, not the compensation failure"))))))

;; ---------------------------------------------------------------------------
;; Original error still surfaces to the caller
;; ---------------------------------------------------------------------------

(deftest test-original-critical-failure-still-thrown-after-compensations
  (with-redefs [http/call (fn [_ {:keys [url]}]
                            (if (= "http://b" url)
                              (http/err :backend-error "500")
                              (http/ok {})))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Critical step failed"
          (run-sync!
            (graph [{:id "a" :url "http://a" :method "GET" :deps []
                     :compensation {:url "http://undo-a" :method "DELETE"}}
                    {:id "b" :url "http://b" :method "GET" :deps ["a"]
                     :critical true}]))))))

;; ---------------------------------------------------------------------------
;; Compensation URL / body can reference the step's own or prior steps' data
;; ---------------------------------------------------------------------------

(deftest test-compensation-can-reference-step-data-via-input-mapping
  (let [captured-params (atom nil)]
    (with-redefs [http/call (fn [_ {:keys [url params]}]
                              (cond
                                (= url "http://create") (http/ok {:id "cust-123"})
                                (= url "http://b")      (http/err :backend-error "500")
                                (= url "http://undo-create") (do (reset! captured-params params)
                                                                 (http/ok {}))
                                :else                    (http/ok {})))]
      (try
        (run-sync!
          (graph [{:id "create" :url "http://create" :method "POST" :deps []
                   :compensation {:url    "http://undo-create"
                                  :method "DELETE"
                                  :input_mapping
                                  {:id {:source "step" :step_id "create" :key "id"}}}}
                  {:id "b" :url "http://b" :method "GET" :deps ["create"]
                   :critical true}]))
        (catch clojure.lang.ExceptionInfo _))
      (is (= "cust-123" (get @captured-params :id))
          "compensation's input_mapping resolved from the created customer's id"))))

;; ---------------------------------------------------------------------------
;; Parallel steps in the same wave — successful sibling still compensates
;; ---------------------------------------------------------------------------

(deftest test-parallel-siblings-successful-one-compensates
  (let [calls (atom [])]
    (with-redefs [http/call (fn [_ {:keys [url method]}]
                              (swap! calls conj [(name method) url])
                              (if (= "http://sibling-fail" url)
                                (http/err :backend-error "500")
                                (http/ok {})))]
      (try
        (run-sync!
          (graph [{:id "sibling-ok" :url "http://sibling-ok" :method "POST" :deps []
                   :compensation {:url "http://undo-sibling-ok" :method "DELETE"}}
                  {:id "sibling-fail" :url "http://sibling-fail" :method "POST" :deps []
                   :critical true}]))
        (catch clojure.lang.ExceptionInfo _))
      (is (some #(= ["delete" "http://undo-sibling-ok"] %) @calls)
          "sibling that succeeded should have its compensation run when the parallel sibling fails"))))
