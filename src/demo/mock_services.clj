(ns demo.mock-services
  (:require [jsonista.core :as json]
            [clojure.string :as str]))

(defn- json-ok [body]
  {:status  200
   :headers {"Content-Type" "application/json"}
   :body    (json/write-value-as-string body)})

(defn- user-handler [request]
  (let [user-id (last (str/split (:uri request) #"/"))]
    (json-ok
     {:data {:id          user-id
             :internal_id (str "internal-" user-id)
             :verified    true
             :profile     {:first_name "alice"
                           :last_name  "smith"
                           :avatar     {:url (str "https://i.pravatar.cc/150?u=" user-id)}}
             :contact     {:emails [{:address (str user-id "@example.com")}]}}})))

(defn- preferences-handler [_]
  (json-ok
   {:data {:ui     {:theme "dark"}
           :locale {:language "en-US"}}}))

(defn- activity-handler [_]
  (json-ok
   {:data {:pagination {:total 3}
           :items      [{:title "Designing Data-Intensive Applications"}
                        {:title "Clojure for the Brave and True"}
                        {:title "Structure and Interpretation of Computer Programs"}]}}))

(defn- orders-handler [_]
  (json-ok
   {:id                      (str "ord-" (System/currentTimeMillis))
    :status                  "confirmed"
    :total_amount            149.97
    :estimated_delivery_date "2026-03-18"
    :line_items              [{:sku "SKU-001" :quantity 2 :price 49.99}
                              {:sku "SKU-002" :quantity 1 :price 49.99}]}))

(defn- notify-handler [_]
  (json-ok {:success true}))

(defn- reserve-handler [_]
  (json-ok {:reserved true}))

(defn- not-found [request]
  {:status  404
   :headers {"Content-Type" "application/json"}
   :body    (json/write-value-as-string {:error "not found" :uri (:uri request)})})

(defn handler [request]
  (let [method (:request-method request)
        uri    (:uri request)]
    (cond
      (and (= :get  method) (str/starts-with? uri "/api/v1/users/"))       (user-handler request)
      (and (= :get  method) (str/starts-with? uri "/api/v1/preferences/")) (preferences-handler request)
      (and (= :get  method) (= uri "/api/v1/activity"))                    (activity-handler request)
      (and (= :post method) (= uri "/api/v1/orders"))                      (orders-handler request)
      (and (= :post method) (= uri "/api/v1/notify"))                      (notify-handler request)
      (and (= :post method) (= uri "/api/v1/reserve"))                     (reserve-handler request)
      :else                                                                 (not-found request))))
