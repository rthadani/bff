(ns bff.sdl
  "SDL emitter for the intermediate schema map from bff.schema-builder."
  (:require [clojure.string :as str]))

(defn- render-type
  "Named type, (non-null T), or (list T). Nests."
  [t]
  (cond
    (or (symbol? t) (keyword? t)) (name t)
    (string? t)                   t
    (seq? t)
    (case (first t)
      non-null (str (render-type (second t)) "!")
      list     (str "[" (render-type (second t)) "]"))))

(defn- render-default [v]
  (cond
    (string? v)  (pr-str v)
    (nil? v)     "null"
    (boolean? v) (str v)
    (number? v)  (str v)
    (vector? v)  (str "[" (str/join ", " (map render-default v)) "]")
    (seq? v)     (str "[" (str/join ", " (map render-default v)) "]")
    :else        (pr-str v)))

(defn- render-arg [[arg-name arg-def]]
  (let [base (str (name arg-name) ": " (render-type (:type arg-def)))]
    (if (contains? arg-def :default-value)
      (str base " = " (render-default (:default-value arg-def)))
      base)))

(defn- render-args [args]
  (if (empty? args)
    ""
    (str "(" (str/join ", " (map render-arg args)) ")")))

(defn- render-field [[field-name field-def]]
  (str "  " (name field-name)
       (render-args (:args field-def))
       ": " (render-type (:type field-def))))

(defn- render-description [desc]
  (when (and desc (not (str/blank? desc)))
    (str "\"\"\"\n" desc "\n\"\"\"\n")))

(defn- render-block
  [keyword type-name type-def]
  (str (render-description (:description type-def))
       keyword " " (name type-name) " {\n"
       (->> (:fields type-def)
            (map render-field)
            (str/join "\n"))
       "\n}"))

(defn- render-scalar [scalar-name description]
  (str (render-description description)
       "scalar " (name scalar-name)))

(defn emit-sdl
  "Return SDL text for a schema map."
  [{:keys [scalars objects input-objects queries mutations]}]
  (->> (concat
         (map (fn [[k v]] (render-scalar k (:description v))) scalars)
         (map (fn [[k v]] (render-block "type"  k v)) objects)
         (map (fn [[k v]] (render-block "input" k v)) input-objects)
         (when (seq queries)
           [(render-block "type" :Query    {:fields queries})])
         (when (seq mutations)
           [(render-block "type" :Mutation {:fields mutations})]))
       (str/join "\n\n")))
