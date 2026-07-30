(ns bff.interop
  "Boundary conversion between Clojure and Java implementations of BFF
   extension points. Java authors expect String-keyed java.util.Map and
   java.util.List; the rest of the engine uses keyword-keyed Clojure maps.")

(defn ->java
  "Recursively convert a Clojure value into structures a Java caller expects:
     • maps → java.util.Map with String keys
     • sequential collections → java.util.List
     • keywords → String (via name)
     • everything else passes through untouched."
  [x]
  (cond
    (keyword? x) (name x)

    (map? x)
    (let [m (java.util.LinkedHashMap.)]
      (doseq [[k v] x]
        (.put m (if (keyword? k) (name k) (str k)) (->java v)))
      m)

    (or (vector? x) (seq? x) (list? x))
    (let [l (java.util.ArrayList.)]
      (doseq [v x] (.add l (->java v)))
      l)

    :else x))

(defn ->clj
  "Recursively convert Java collections returned from an interop call back
   into keyword-keyed Clojure maps and vectors. Idempotent on Clojure data."
  [x]
  (cond
    (instance? java.util.Map x)
    (persistent!
     (reduce (fn [m e]
               (assoc! m
                       (let [k (.getKey ^java.util.Map$Entry e)]
                         (if (string? k) (keyword k) k))
                       (->clj (.getValue ^java.util.Map$Entry e))))
             (transient {})
             (.entrySet ^java.util.Map x)))

    (instance? java.util.List x)
    (into [] (map ->clj) x)

    :else x))
