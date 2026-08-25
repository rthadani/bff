(ns bff.jq-engine
  (:require [jsonista.core :as json])
  (:import
   [net.thisptr.jackson.jq JsonQuery Scope BuiltinFunctionLoader Versions Output]
   [com.fasterxml.jackson.databind ObjectMapper JsonNode]
   [java.util ArrayList]))


(def ^:private ^ObjectMapper mapper
  (ObjectMapper.))

(def ^:private ^Scope root-scope
  (let [scope (Scope/newEmptyScope)]
    (.loadFunctions (BuiltinFunctionLoader/getInstance) Versions/JQ_1_6 scope)
    scope))

(defn compile-query
  ^JsonQuery [^String expr]
  (JsonQuery/compile expr Versions/JQ_1_6))

(defn to-node
  ^JsonNode [data]
  (.readTree mapper ^String (json/write-value-as-string data)))

(defn execute-node
  [^JsonQuery query ^JsonNode node]
  (let [results (ArrayList.)]
    (.apply query
            root-scope
            node
            (reify Output
              (emit [_ output]
                (.add results (json/read-value (.writeValueAsString mapper output)
                                               json/keyword-keys-object-mapper)))))
    (case (.size results)
      0 nil
      1 (first results)
      (vec results))))

(defn execute
  [^JsonQuery query data]
  (execute-node query (to-node data)))

