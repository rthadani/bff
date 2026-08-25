(ns bff.http-client-test
  (:require [clojure.test :refer [deftest is testing]]
            [bff.http-client :as http])
  (:import [io.github.rthadani.bff BffHttpClient BffHttpClient$Request BffHttpClient$Response]))

;; ---------------------------------------------------------------------------
;; ->result: raw {:status :body} → tagged {:status :ok/:error}
;; ---------------------------------------------------------------------------

(deftest ->result-2xx-parsed-json
  (let [r (http/->result {:status 200 :body "{\"id\":1,\"name\":\"a\"}"} :s)]
    (is (= :ok (:status r)))
    (is (= {:id 1 :name "a"} (:data r)))))

(deftest ->result-2xx-non-json-body-kept-raw
  (let [r (http/->result {:status 200 :body "plain text"} :s)]
    (is (= :ok (:status r)))
    (is (= "plain text" (:data r)))))

(deftest ->result-nil-status-is-no-response
  (let [r (http/->result {:status nil :body nil} :s)]
    (is (= :error (:status r)))
    (is (= :no-response (get-in r [:error :code])))))

(deftest ->result-maps-common-http-statuses
  (doseq [[status code] [[400 :bad-request]
                         [401 :unauthorized]
                         [403 :forbidden]
                         [404 :not-found]
                         [422 :unprocessable]
                         [500 :backend-error]
                         [503 :backend-error]
                         [418 :unexpected-status]]]
    (let [r (http/->result {:status status :body "{}"} :s)]
      (is (= :error (:status r)) (str "status " status))
      (is (= code (get-in r [:error :code])) (str "status " status))
      (is (= status (:http-status r))))))

;; ---------------------------------------------------------------------------
;; IFn as :http-client
;; ---------------------------------------------------------------------------

(deftest ifn-client-raw-response-mapped
  (let [client (fn [_req] {:status 200 :body "{\"ok\":true}"})
        result (http/call client {:method :get :url "http://x" :step-id :s})]
    (is (= :ok (:status result)))
    (is (= {:ok true} (:data result)))))

(deftest ifn-client-tagged-response-passed-through
  (let [tagged (http/ok {:already :tagged})
        client (fn [_req] tagged)
        result (http/call client {:method :get :url "http://x" :step-id :s})]
    (is (= tagged result))))

(deftest ifn-client-error-status-tagged
  (let [client (fn [_req] {:status 404 :body "{\"msg\":\"nope\"}"})
        result (http/call client {:method :get :url "http://x" :step-id :s})]
    (is (= :error (:status result)))
    (is (= :not-found (get-in result [:error :code])))
    (is (= 404 (:http-status result)))))

(deftest ifn-client-exception-becomes-unexpected
  (let [client (fn [_req] (throw (RuntimeException. "boom")))
        result (http/call client {:method :get :url "http://x" :step-id :s})]
    (is (= :error (:status result)))
    (is (= :unexpected (get-in result [:error :code])))
    (is (= "boom" (get-in result [:error :detail :cause])))))

(deftest ifn-client-sees-full-request-map
  (let [captured (atom nil)
        client   (fn [req] (reset! captured req) {:status 204 :body nil})]
    (http/call client {:method :post
                       :url "http://x/y"
                       :params {"q" "1"}
                       :body {:k :v}
                       :headers {"authorization" "Bearer T"}
                       :step-id :s})
    (is (= :post (:method @captured)))
    (is (= "http://x/y" (:url @captured)))
    (is (= {"q" "1"} (:params @captured)))
    (is (= {:k :v} (:body @captured)))
    (is (= {"authorization" "Bearer T"} (:headers @captured)))
    (is (= :s (:step-id @captured)))))

;; ---------------------------------------------------------------------------
;; Java BffHttpClient as :http-client
;; ---------------------------------------------------------------------------

(defn- capturing-java-client [captured response]
  (reify BffHttpClient
    (send [_ req]
      (reset! captured req)
      response)))

(deftest java-client-request-fields-populated
  (let [captured (atom nil)
        client   (capturing-java-client captured (BffHttpClient$Response. 200 "{\"id\":42}"))
        result   (http/call client
                            {:method :patch
                             :url "http://x/y"
                             :params {"a" "b"}
                             :body {:v 1}
                             :headers {"h" "1"}
                             :step-id :the-step})]
    (is (= :ok (:status result)))
    (is (= {:id 42} (:data result)))
    (let [^BffHttpClient$Request req @captured]
      (is (= "PATCH" (.method req)))
      (is (= "http://x/y" (.url req)))
      (is (= {"a" "b"} (.queryParams req)))
      (is (= {:v 1} (.body req)))
      (is (= {"h" "1"} (.headers req)))
      (is (= "the-step" (.stepId req))))))

(deftest java-client-error-status-mapped
  (let [client (reify BffHttpClient
                 (send [_ _] (BffHttpClient$Response. 401 "{}")))
        result (http/call client {:method :get :url "http://x" :step-id :s})]
    (is (= :error (:status result)))
    (is (= :unauthorized (get-in result [:error :code])))
    (is (= 401 (:http-status result)))))

(deftest java-client-exception-becomes-unexpected
  (let [client (reify BffHttpClient
                 (send [_ _] (throw (RuntimeException. "kaboom"))))
        result (http/call client {:method :get :url "http://x" :step-id :s})]
    (is (= :error (:status result)))
    (is (= :unexpected (get-in result [:error :code])))
    (is (= "kaboom" (get-in result [:error :detail :cause])))))

;; ---------------------------------------------------------------------------
;; default-client / hato options
;; ---------------------------------------------------------------------------

(deftest default-client-with-no-opts-returns-hato-record
  (let [c (http/default-client)]
    (is (satisfies? http/BffHttp c))
    (is (= {} (:hato-opts c)))))

(deftest default-client-carries-hato-opts
  (let [opts {:connect-timeout 1234}
        c    (http/default-client opts)]
    (is (satisfies? http/BffHttp c))
    (is (= opts (:hato-opts c)))))
