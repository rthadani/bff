(ns bff.schema-builder
  (:require [bff.executor :as executor]
            [bff.error :as error]
            [com.walmartlabs.lacinia.schema :as schema]
            [com.walmartlabs.lacinia.resolve :as resolve]
            [clojure.string :as str]))

(defn- parse-type-str
  [t]
  (let [t (str/trim t)]
    (cond
      (and (str/starts-with? t "[") (str/ends-with? t "]!"))
      (let [inner (-> t (subs 1 (- (count t) 2)) str/trim)]
        (if (str/ends-with? inner "!")
          (list 'non-null (list 'list (list 'non-null (symbol (str/replace inner #"!$" "")))))
          (list 'non-null (list 'list (symbol inner)))))

      (and (str/starts-with? t "[") (str/ends-with? t "]"))
      (let [inner (-> t (subs 1 (dec (count t))) str/trim)]
        (if (str/ends-with? inner "!")
          (list 'list (list 'non-null (symbol (str/replace inner #"!$" ""))))
          (list 'list (symbol inner))))

      (str/ends-with? t "!")
      (list 'non-null (symbol (str/replace t "!" "")))

      :else
      (symbol t))))

(defn- build-object-type
  [output-type-spec]
  {(keyword (:name output-type-spec))
   {:description (:description output-type-spec "")
    :fields
    (->> (:fields output-type-spec)
         (map (fn [[k v]]
                [(keyword k)
                 {:type (parse-type-str (if (map? v) (:type v) v))}]))
         (into {}))}})

(defn- build-input-type
  [input-type-spec]
  {(keyword (:name input-type-spec))
   {:fields
    (->> (:fields input-type-spec)
         (map (fn [[k v]]
                [(keyword k) {:type (parse-type-str v)}]))
         (into {}))}})

(defn- run-task-sync
  [task]
  (let [p (java.util.concurrent.CompletableFuture.)]
    (task #(.complete p %) #(.completeExceptionally p %))
    (.get p)))

(defn- make-resolver
  [endpoint]
  (fn [ctx args _val]
    (let [request-ctx (or (:request ctx) {})
          {:keys [data errors]}
          (run-task-sync (executor/run-endpoint endpoint args request-ctx))]
      (if (seq errors)
        ;; Surface partial errors while still returning available data
        (resolve/resolve-as data (map (fn [e]
                                        {:message    (:message e)
                                         :extensions (:extensions e)})
                                      errors))
        data))))

(defn- build-args [args-spec]
  (->> args-spec
       (map (fn [[k v]]
              [(keyword k)
               (cond-> {:type (parse-type-str (:type v (str v)))}
                 (:default v) (assoc :default-value (:default v)))]))
       (into {})))

(defn- build-operation [endpoint]
  {(keyword (:name endpoint))
   (cond-> {:type        (keyword (get-in endpoint [:output_type :name]))
            :description (:description endpoint "")
            :args        (build-args (:args endpoint {}))
            :resolve     (error/wrap-resolver-errors (make-resolver endpoint))}
     (:deprecation_reason endpoint)
     (assoc :deprecated (:deprecation_reason endpoint)))})


(defn build-schema
  [spec]
  (let [endpoints (-> spec :endpoints)
        queries   (filter #(= (:type %) "query") endpoints)
        mutations (filter #(= (:type %) "mutation") endpoints)

        objects   (->> endpoints
                       (map #(build-object-type (:output_type %)))
                       (apply merge))

        input-objs (->> (get spec :input_types [])
                        (map build-input-type)
                        (apply merge {}))]

    (-> {:objects        objects
         :input-objects  input-objs
         :queries        (->> queries  (map build-operation) (apply merge {}))
         :mutations      (->> mutations (map build-operation) (apply merge {}))}
        schema/compile)))
