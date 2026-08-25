(ns bff.http-client
  (:require [hato.client :as hato]
            [jsonista.core :as json]
            [clojure.string :as str])
  (:import [java.net ConnectException SocketTimeoutException]
           [java.net.http HttpTimeoutException]
           [io.github.rthadani.bff BffHttpClient BffHttpClient$Request]))

(declare ->result)

(defprotocol BffHttp
  "A single-request HTTP transport. Extended below to plain fns (the natural
   Clojure implementation), the Java BffHttpClient interface, and BFF's own
   hato-backed default. Implementations return either a raw
   {:status int :body str} that BFF maps through `->result`, or a fully tagged
   {:status :ok/:error ...} map that is passed through untouched."
  (do-call [this req]))

(def ^:private base-client-opts
  {:connect-timeout 5000
   :redirect-policy :always
   :version         :http-2})

(def ^:private client-cache (atom {}))

(defn- client-for [opts]
  (if (empty? opts)
    (or (get @client-cache ::default)
        (get (swap! client-cache
                    #(if (contains? % ::default)
                       %
                       (assoc % ::default (hato/build-http-client base-client-opts))))
             ::default))
    (or (get @client-cache opts)
        (get (swap! client-cache
                    (fn [m]
                      (if (contains? m opts)
                        m
                        (assoc m opts (hato/build-http-client
                                        (merge base-client-opts opts))))))
             opts))))

(def ^:private default-opts
  {:as                :string
   :content-type      :json
   :accept            :json
   :throw-exceptions? false
   :timeout           30000})

(defn ok [data] {:status :ok :data data})
(defn err
  "Build a tagged error result. Optional `detail` (map) attaches free-form
   context; optional `http-status` (int) records the upstream HTTP status
   code when the failure originated from an HTTP response, so a step's
   spec-level `errors:` mapping can remap by status."
  ([code msg]                     (err code msg nil nil))
  ([code msg detail]               (err code msg detail nil))
  ([code msg detail http-status]
   (cond-> {:status :error
            :error  (cond-> {:code code :message msg}
                      detail (assoc :detail detail))}
     http-status (assoc :http-status http-status))))

(defn error? [result] (= :error (:status result)))

(defn- parse-body [body]
  (when (and body (not (str/blank? body)))
    (try (json/read-value body json/keyword-keys-object-mapper)
         (catch Exception _ body))))

(defn ->result
  "Map a raw HTTP response ({:status int :body string}) to a tagged result.
   Public so implementations of `BffHttp` can reuse the same status-code
   error mapping."
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
           {:step step-id :body parsed}
           status)

      (= 401 status)
      (err :unauthorized
           "Backend returned 401 — check auth header forwarding"
           {:step step-id}
           status)

      (= 403 status)
      (err :forbidden
           "Backend returned 403"
           {:step step-id :body parsed}
           status)

      (= 404 status)
      (err :not-found
           "Backend resource not found"
           {:step step-id :body parsed}
           status)

      (= 422 status)
      (err :unprocessable
           "Backend validation error"
           {:step step-id :body parsed}
           status)

      (<= 500 status 599)
      (err :backend-error
           (str "Backend returned " status)
           {:step step-id :body parsed}
           status)

      :else
      (err :unexpected-status
           (str "Unexpected HTTP status " status)
           {:step step-id :body parsed}
           status))))

(defrecord HatoClient [hato-opts]
  BffHttp
  (do-call [_ {:keys [method url params body headers step-id]
               :or   {method :get headers {}}}]
    (try
      (let [req  (cond-> (merge default-opts
                                {:http-client (client-for hato-opts)
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
             {:step step-id :cause (.getMessage e)})))))

(defn default-client
  "The built-in hato-backed BffHttp implementation. `hato-opts` is merged into
   hato's `build-http-client` options; pass nil for the shared defaults."
  ([]         (default-client nil))
  ([hato-opts] (->HatoClient (or hato-opts {}))))

(extend-protocol BffHttp
  clojure.lang.IFn
  (do-call [f {:keys [step-id] :as req}]
    (let [resp (f req)]
      (if (#{:ok :error} (:status resp))
        resp
        (->result {:status (:status resp) :body (:body resp)} step-id))))

  BffHttpClient
  (do-call [client {:keys [method url params body headers step-id]
                    :or   {method :get headers {}}}]
    (let [jreq (BffHttpClient$Request.
                 (str/upper-case (name method))
                 url
                 (or params {})
                 body
                 (or headers {})
                 (some-> step-id name))
          resp (.send client jreq)]
      (->result {:status (.status resp) :body (.body resp)} step-id))))

(defn call
  "Route an HTTP step through `client` (any BffHttp — hato default, plain fn,
   or Java BffHttpClient). Errors are captured as tagged results — this never
   throws."
  [client {:keys [url step-id] :as req}]
  (try
    (do-call client req)
    (catch Exception e
      (err :unexpected
           (str "Unexpected error calling " url)
           {:step step-id :cause (.getMessage e)}))))
