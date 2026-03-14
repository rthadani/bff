(ns bff.http-client
  (:require [hato.client :as hato]
            [jsonista.core :as json]
            [clojure.string :as str])
  (:import [java.net ConnectException SocketTimeoutException]
           [java.net.http HttpTimeoutException]))

(def ^:private default-client
  (delay
    (hato/build-http-client
     {:connect-timeout 5000
      :redirect-policy :always
      :version :http-2})))

(def ^:private default-opts
  {:as                :string
   :content-type      :json
   :accept            :json
   :throw-exceptions? false   ; we handle errors ourselves
   :timeout           30000})

(defn ok [data]    {:status :ok    :data  data})
(defn err [code msg & [detail]]
  {:status :error
   :error  (cond-> {:code code :message msg}
             detail (assoc :detail detail))})

(defn error? [result] (= :error (:status result)))

(defn- parse-body [body]
  (when (and body (not (str/blank? body)))
    (try (json/read-value body json/keyword-keys-object-mapper)
         (catch Exception _ body))))   ; return raw string if not JSON

(defn- ->result
  "Convert a hato response map to a tagged result."
  [{:keys [status body]} step-id]
  (let [parsed (parse-body body)]
    (cond
      (nil? status)
      (err :no-response "No response received" {:step step-id})

      (<= 200 status 299)
      (ok parsed)

      (= 400 status)
      (err :bad-request
           (str "Backend returned 400")
           {:step step-id :body parsed})

      (= 401 status)
      (err :unauthorized
           "Backend returned 401 — check auth header forwarding"
           {:step step-id})

      (= 403 status)
      (err :forbidden
           "Backend returned 403"
           {:step step-id :body parsed})

      (= 404 status)
      (err :not-found
           "Backend resource not found"
           {:step step-id :body parsed})

      (= 422 status)
      (err :unprocessable
           "Backend validation error"
           {:step step-id :body parsed})

      (<= 500 status 599)
      (err :backend-error
           (str "Backend returned " status)
           {:step step-id :body parsed})

      :else
      (err :unexpected-status
           (str "Unexpected HTTP status " status)
           {:step step-id :body parsed}))))


(defn call
  [{:keys [method url params body headers step-id]
    :or   {method :get headers {}}}]
  (try
    (let [req  (cond-> (merge default-opts
                              {:http-client @default-client
                               :headers     headers})
                 (seq params) (assoc :query-params params)
                 (seq body)   (assoc :form-params body))
          resp (case method
                 :get    (hato/get    url req)
                 :post   (hato/post   url req)
                 :put    (hato/put    url req)
                 :patch  (hato/patch  url req)
                 :delete (hato/delete url req)
                 (throw (ex-info (str "Unsupported HTTP method: " method)
                                 {:method method})))]
      (->result resp step-id))

    (catch ConnectException e
      (err :connection-refused
           (str "Could not connect to " url)
           {:step step-id :cause (.getMessage e)}))

    (catch SocketTimeoutException e
      (err :timeout
           (str "Request to " url " timed out")
           {:step step-id :cause (.getMessage e)}))

    (catch HttpTimeoutException e
      (err :timeout
           (str "Request to " url " timed out")
           {:step step-id :cause (.getMessage e)}))

    (catch Exception e
      (err :unexpected
           (str "Unexpected error calling " url)
           {:step step-id :cause (.getMessage e)}))))
