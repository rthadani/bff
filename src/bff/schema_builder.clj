(ns bff.schema-builder
  (:require [bff.executor :as executor]
            [bff.error :as error]
            [bff.scalar :as scalar]
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

(defn- nested-type?
  [v]
  (and (map? v) (contains? v :name) (contains? v :fields)))

(defn- build-object-type
  [output-type-spec]
  {(keyword (:name output-type-spec))
   {:description (:description output-type-spec "")
    :fields
    (->> (:fields output-type-spec)
         (map (fn [[k v]]
                [(keyword k)
                 (if (nested-type? v)
                   {:type (keyword (:name v))}
                   {:type (parse-type-str (if (map? v) (:type v) v))})]))
         (into {}))}})

(defn- collect-object-types
  [output-type-spec]
  (apply merge
    (build-object-type output-type-spec)
    (->> (:fields output-type-spec)
         vals
         (filter nested-type?)
         (map collect-object-types))))

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

(defn- error->graphql
  "Normalise an error entry for Lacinia's :extensions channel.

   Step-level errors already arrive with :extensions populated (see
   bff.error/step-errors). Resolver-returned errors typically don't — they
   may set :code at the top level. Lift a top-level :code into
   :extensions.code so downstream clients see a consistent shape."
  [e]
  (let [existing (or (:extensions e) {})
        with-code (cond-> existing
                    (and (:code e) (not (contains? existing :code)))
                    (assoc :code (:code e)))]
    {:message    (:message e)
     :extensions with-code}))

(defn- make-resolver
  [endpoint extensions]
  (fn [ctx args _val]
    (let [request-ctx (or (:request ctx) {})
          {:keys [data errors]}
          (run-task-sync (executor/run-endpoint endpoint args request-ctx extensions))]
      (if (seq errors)
        ;; Surface partial errors while still returning available data
        (resolve/resolve-as data (map error->graphql errors))
        data))))

(defn- build-args [args-spec]
  (->> args-spec
       (map (fn [[k v]]
              [(keyword k)
               (cond-> {:type (parse-type-str (:type v (str v)))}
                 (:default v) (assoc :default-value (:default v)))]))
       (into {})))

(defn- build-operation [endpoint extensions]
  {(keyword (:name endpoint))
   (cond-> {:type        (keyword (get-in endpoint [:output_type :name]))
            :description (:description endpoint "")
            :args        (build-args (:args endpoint {}))
            :resolve     (error/wrap-resolver-errors (make-resolver endpoint extensions))}
     (:deprecation_reason endpoint)
     (assoc :deprecated (:deprecation_reason endpoint)))})


(defn- build-scalar-entry
  "Turn one spec scalar declaration + its config impl into a Lacinia
   :scalars entry. Throws if the spec declares a scalar with no impl."
  [scalar-cfgs {:keys [name description]}]
  (let [impl (get scalar-cfgs name)]
    (when (nil? impl)
      (throw (ex-info (str "Scalar '" name "' declared in spec but no "
                           "implementation supplied under :scalars")
                      {:name name :registered (keys scalar-cfgs)})))
    [(keyword name)
     (cond-> {:parse     (fn [v] (scalar/parse     impl v))
              :serialize (fn [v] (scalar/serialize impl v))}
       description (assoc :description description))]))

(defn- build-scalars [spec-scalars scalar-cfgs]
  (into {} (map #(build-scalar-entry scalar-cfgs %)) spec-scalars))

(defn build-schema
  "Compile a Lacinia schema from `spec`. `extensions` is the caller-owned
   config map ({:enrichers :validators :transformers :resolvers :retry-hooks
   :cache :scalars}); it is closed over every resolver so runtime requests
   carry it into bff.executor/run-endpoint."
  ([spec] (build-schema spec {}))
  ([spec extensions]
   (let [endpoints (-> spec :endpoints)
         queries   (filter #(= (:type %) "query") endpoints)
         mutations (filter #(= (:type %) "mutation") endpoints)

         objects   (->> endpoints
                        (map #(collect-object-types (:output_type %)))
                        (apply merge))

         input-objs (->> (get spec :input_types [])
                         (map build-input-type)
                         (apply merge {}))

         scalars    (build-scalars (get spec :scalars []) (:scalars extensions))]

     (-> (cond-> {:objects        objects
                  :input-objects  input-objs
                  :queries        (->> queries  (map #(build-operation % extensions)) (apply merge {}))
                  :mutations      (->> mutations (map #(build-operation % extensions)) (apply merge {}))}
           (seq scalars) (assoc :scalars scalars))
         schema/compile))))
