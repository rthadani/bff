(ns bff.main
  (:require [bff.core :as bff]
            [ring.adapter.jetty :refer [run-jetty]]
            [taoensso.timbre :as log])
  (:gen-class))

(defn -main [& args]
  (let [spec-path (or (first args) "bff-spec.yaml")
        port      (Integer/parseInt (or (System/getenv "PORT") "8080"))
        handler   (bff/create-handler spec-path)]
    (log/infof "Starting BFF on port %d" port)
    (run-jetty handler {:port port :join? true})))
