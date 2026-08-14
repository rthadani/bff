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
  "Return a seq of single-entry {TypeName typeDef} maps for the top-level
   type and every nested inline type. Returns [] when passed a bare string
   type reference (nothing to collect — the definition is elsewhere)."
  [output-type-spec]
  (if (map? output-type-spec)
    (cons (build-object-type output-type-spec)
          (mapcat collect-object-types
                  (filter nested-type? (vals (:fields output-type-spec)))))
    []))

(defn- build-input-type
  [input-type-spec]
  {(keyword (:name input-type-spec))
   {:fields
    (->> (:fields input-type-spec)
         (map (fn [[k v]]
                [(keyword k) {:type (parse-type-str v)}]))
         (into {}))}})

(defn- merge-type-def
  "Merge two definitions of the same type. Fields are unioned; fields present
   in both must have identical type declarations. Description falls back to
   whichever definition supplied one."
  [kind type-name a b]
  (let [fields (reduce-kv
                 (fn [acc field-name field-def]
                   (if-let [existing (get acc field-name)]
                     (if (= (:type existing) (:type field-def))
                       acc
                       (throw (ex-info (format "Conflicting types for field '%s' in %s '%s'"
                                               (name field-name) kind (name type-name))
                                       {:kind     kind
                                        :type     type-name
                                        :field    field-name
                                        :existing (:type existing)
                                        :incoming (:type field-def)})))
                     (assoc acc field-name field-def)))
                 (:fields a)
                 (:fields b))]
    (assoc (merge a b) :fields fields)))

(defn- merge-type-maps
  "Reduce a seq of {typeName typeDef} maps into one. Duplicate type names
   are merged field-by-field via merge-type-def."
  [kind type-maps]
  (reduce
    (fn [acc one]
      (reduce-kv
        (fn [acc' type-name new-def]
          (if-let [existing (get acc' type-name)]
            (assoc acc' type-name (merge-type-def kind type-name existing new-def))
            (assoc acc' type-name new-def)))
        acc
        one))
    {}
    type-maps))

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

(defn- output-type-name
  "output_type is either an inline definition {:name X :fields ...} or a
   bare string referencing a top-level output_types entry."
  [endpoint]
  (let [ot (:output_type endpoint)]
    (keyword (if (map? ot) (:name ot) ot))))

(defn- build-operation [endpoint extensions]
  {(keyword (:name endpoint))
   (cond-> {:type        (output-type-name endpoint)
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

(defn build-schema-map
  "Intermediate schema map, before schema/compile. Public so SDL emission
   can share the same source."
  ([spec] (build-schema-map spec {}))
  ([spec extensions]
   (let [endpoints (:endpoints spec)
         queries   (filter #(= (:type %) "query") endpoints)
         mutations (filter #(= (:type %) "mutation") endpoints)

         objects   (merge-type-maps
                     "output type"
                     (concat
                       (map build-object-type (get spec :output_types []))
                       (mapcat #(collect-object-types (:output_type %)) endpoints)))

         input-objs (merge-type-maps
                      "input type"
                      (map build-input-type (get spec :input_types [])))

         scalars    (build-scalars (get spec :scalars []) (:scalars extensions))]

     (cond-> {:objects        objects
              :input-objects  input-objs
              :queries        (->> queries  (map #(build-operation % extensions)) (apply merge {}))
              :mutations      (->> mutations (map #(build-operation % extensions)) (apply merge {}))}
       (seq scalars) (assoc :scalars scalars)))))

(defn build-schema
  "Compile a Lacinia schema from `spec`. `extensions` is the caller-owned
   config map ({:enrichers :validators :transformers :resolvers :retry-hooks
   :cache :scalars}); it is closed over every resolver so runtime requests
   carry it into bff.executor/run-endpoint.

   Object types can be declared inline on each endpoint's `output_type` or at
   the top level under `output_types:` (referenced from an endpoint by bare
   name). Duplicate type names are merged field-by-field; conflicting types
   on a shared field name throw. Input types (`input_types:`) get the same
   treatment."
  ([spec] (build-schema spec {}))
  ([spec extensions]
   (schema/compile (build-schema-map spec extensions))))
