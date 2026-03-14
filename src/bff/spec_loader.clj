(ns bff.spec-loader
  (:require [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [bff.jq-engine :as jq]))

(defn load-spec
  [resource-path]
  (if-let [r (io/resource resource-path)]
    (-> r slurp yaml/parse-string)
    (throw (ex-info (str "Spec file not found: " resource-path)
                    {:path resource-path}))))

(defn- compile-mapping-entry [mapping]
  (if-let [expr (:jq mapping)]
    (assoc mapping :compiled-jq (jq/compile-query expr))
    mapping))

(defn- compile-param-map [param-map]
  (when param-map
    (->> param-map
         (map (fn [[k v]] [k (compile-mapping-entry v)]))
         (into {}))))

(defn- compile-step [step]
  (-> step
      (update :input_mapping compile-param-map)
      (update :body_mapping  compile-param-map)))

(defn- preload-transformer! [transformer]
  (when (and transformer (:ns transformer))
    (require (symbol (:ns transformer)))))

(defn- compile-endpoint [endpoint]
  (preload-transformer! (:transformer endpoint))
  (-> endpoint
      (update :backend_chain #(mapv compile-step %))
      (update :output_mapping compile-param-map)))

(defn compile-spec
  [spec]
  (update spec :endpoints #(mapv compile-endpoint %)))

(defn load-and-compile [resource-path]
  (-> resource-path load-spec compile-spec))
