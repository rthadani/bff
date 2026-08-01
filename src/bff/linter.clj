(ns bff.linter
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]))

(def ^:private problem-schema
  [:map
   [:severity [:enum :error :warning]]
   [:path     :string]
   [:message  :string]
   [:node     :any]])

(defn problem?
  [x]
  (m/validate problem-schema x))

(def ^:private http-method
  [:enum "GET" "POST" "PUT" "PATCH" "DELETE"])

(def ^:private mapping-source
  [:enum "args" "step" "value" "ctx" :args :step :value :ctx])

(def ^:private mapping-entry
  [:map {:closed true}
   [:source                    mapping-source]
   [:key         {:optional true} :string]
   [:step_id     {:optional true} :string]
   [:value       {:optional true} :any]
   [:jq          {:optional true} :string]
   [:compiled-jq {:optional true} :any]
   [:optional    {:optional true} :boolean]
   [:format      {:optional true} :string]])

(def ^:private extension-ref
  [:or
   [:map {:closed true} [:key :string]]
   [:map {:closed true} [:ns :string] [:fn :string]]])

(def ^:private arg-schema
  [:or
   :string
   [:map {:closed true}
    [:type    :string]
    [:default {:optional true} :any]
    [:validate {:optional true}
     [:map {:closed true}
      [:pattern {:optional true} :string]
      [:min     {:optional true} number?]
      [:max     {:optional true} number?]
      [:message {:optional true} :string]]]]])

(def ^:private retry-schema
  [:map {:closed true}
   [:max :int]
   [:on_code [:vector [:or :string :keyword]]]
   [:before_retry {:optional true} extension-ref]])

(def ^:private compensation-schema
  [:map {:closed true}
   [:url    :string]
   [:method http-method]
   [:input_mapping {:optional true} [:map-of :any mapping-entry]]
   [:body_mapping  {:optional true} [:map-of :any mapping-entry]]
   [:extra_headers {:optional true} [:map-of :any :string]]])

(def ^:private backend-step-schema
  [:map {:closed true}
   [:id     :string]
   [:url    :string]
   [:method http-method]
   [:deps          {:optional true} [:vector :string]]
   [:critical      {:optional true} :boolean]
   [:condition     {:optional true} mapping-entry]
   [:input_mapping {:optional true} [:map-of :any mapping-entry]]
   [:body_mapping  {:optional true} [:map-of :any mapping-entry]]
   [:extra_headers {:optional true} [:map-of :any :string]]
   [:cache         {:optional true}
    [:map {:closed true} [:key :string] [:ttl {:optional true} :int]]]
   [:retry         {:optional true} retry-schema]
   [:errors        {:optional true} [:map-of :any :string]]
   [:compensation  {:optional true} compensation-schema]])

(def ^:private object-type-registry
  {::object-type
   [:map {:closed true}
    [:name        :string]
    [:description {:optional true} :string]
    [:fields      [:map-of :any [:or :string [:ref ::object-type]]]]]})

(def ^:private output-type-ref
  [:schema {:registry object-type-registry}
   [:or :string [:ref ::object-type]]])

(def ^:private endpoint-schema
  [:map {:closed true}
   [:name :string]
   [:type [:enum "query" "mutation"]]
   [:output_type output-type-ref]
   [:description        {:optional true} :string]
   [:deprecation_reason {:optional true} :string]
   [:args               {:optional true} [:map-of :any arg-schema]]
   [:backend_chain      {:optional true} [:vector backend-step-schema]]
   [:output_mapping     {:optional true} :any]
   [:validator          {:optional true} extension-ref]
   [:transformer        {:optional true} extension-ref]
   [:resolver           {:optional true} extension-ref]])

(def ^:private input-type-schema
  [:map {:closed true}
   [:name :string]
   [:fields [:map-of :any :string]]])

(def ^:private scalar-schema
  [:map {:closed true}
   [:name :string]
   [:description {:optional true} :string]])

(def ^:private spec-schema
  [:map {:closed true}
   [:endpoints [:vector endpoint-schema]]
   [:forward_headers {:optional true} [:vector :string]]
   [:scalars         {:optional true} [:vector scalar-schema]]
   [:input_types     {:optional true} [:vector input-type-schema]]
   [:output_types    {:optional true}
    [:vector [:schema {:registry object-type-registry} [:ref ::object-type]]]]])

(defn- in->path-string
  [in]
  (reduce (fn [acc seg]
            (cond
              (int? seg) (str acc "[" seg "]")
              :else      (if (empty? acc)
                           (name seg)
                           (str acc "." (name seg)))))
          ""
          in))

(defn- explain-error->problem
  [spec {:keys [in type] :as err}]
  (let [extra?    (= type :malli.core/extra-key)
        node-path (if (seq in) (butlast in) in)]
    {:severity (if extra? :warning :error)
     :path     (in->path-string in)
     :message  (or (me/error-message err) "invalid")
     :node     (if (seq node-path) (get-in spec node-path) spec)}))

(def ^:private built-in-scalars
  #{"String" "Int" "Float" "Boolean" "ID"})

(defn- inline-output-type-names [ot]
  (when (map? ot)
    (cons (:name ot)
          (mapcat inline-output-type-names
                  (filter map? (vals (:fields ot)))))))

(defn- declared-type-names [spec]
  (into built-in-scalars
        (concat (map :name (:scalars spec))
                (map :name (:input_types spec))
                (map :name (:output_types spec))
                (mapcat (comp inline-output-type-names :output_type)
                        (:endpoints spec)))))

(defn- base-type-name [s]
  (-> (str s) (str/replace #"[\[\]!]" "") str/trim))

(defn- source-eq? [entry sym]
  (let [s (:source entry)]
    (or (= s (name sym)) (= s sym))))

(defn- check-duplicate-step-ids [spec]
  (for [[ei endpoint] (map-indexed vector (:endpoints spec))
        [dup n] (frequencies (map :id (:backend_chain endpoint)))
        :when (and dup (> n 1))]
    {:severity :error
     :path     (str "endpoints[" ei "].backend_chain")
     :message  (str "duplicate step id: '" dup "'")
     :node     endpoint}))

(defn- check-deps [spec]
  (for [[ei endpoint] (map-indexed vector (:endpoints spec))
        :let [ids (set (map :id (:backend_chain endpoint)))]
        [si step]    (map-indexed vector (:backend_chain endpoint))
        [di dep]     (map-indexed vector (:deps step))
        :when (not (contains? ids dep))]
    {:severity :error
     :path     (str "endpoints[" ei "].backend_chain[" si "].deps[" di "]")
     :message  (str "deps references unknown step '" dep "'")
     :node     step}))

(defn- mapping-entries-in-step
  "Yield [prefix mapping-map] pairs for every mapping-shaped block on the step."
  [step-path step]
  (keep (fn [k]
          (when-let [m (get step k)]
            [(str step-path "." (name k)) m]))
        [:input_mapping :body_mapping]))

(defn- walk-endpoint-mappings
  "Yield [full-path entry enclosing-node] triples for every source-tagged
   mapping entry in an endpoint. Covers step-level input/body/condition
   plus endpoint-level output_mapping."
  [ei endpoint]
  (concat
    (for [[si step] (map-indexed vector (:backend_chain endpoint))
          :let [step-path (str "endpoints[" ei "].backend_chain[" si "]")]
          [prefix mapping-map] (mapping-entries-in-step step-path step)
          [k entry] mapping-map
          :when (and (map? entry) (:source entry))]
      [(str prefix "." (name k)) entry step])
    (for [[si step] (map-indexed vector (:backend_chain endpoint))
          :when (:condition step)]
      [(str "endpoints[" ei "].backend_chain[" si "].condition")
       (:condition step) step])
    (for [[k entry] (:output_mapping endpoint)
          :when (and (map? entry) (:source entry))]
      [(str "endpoints[" ei "].output_mapping." (name k)) entry endpoint])))

(defn- check-step-refs [spec]
  (for [[ei endpoint] (map-indexed vector (:endpoints spec))
        :let [ids (set (map :id (:backend_chain endpoint)))]
        [path entry node] (walk-endpoint-mappings ei endpoint)
        :when (and (source-eq? entry :step)
                   (not (contains? ids (:step_id entry))))]
    {:severity :error
     :path     path
     :message  (str "references unknown step '" (:step_id entry) "'")
     :node     node}))

(defn- check-arg-refs [spec]
  (for [[ei endpoint] (map-indexed vector (:endpoints spec))
        :let [args (set (map name (keys (:args endpoint))))]
        [path entry node] (walk-endpoint-mappings ei endpoint)
        :when (and (source-eq? entry :args)
                   (not (contains? args (str (:key entry)))))]
    {:severity :error
     :path     path
     :message  (str "references unknown arg '" (:key entry) "'")
     :node     node}))

(defn- inline-output-type-refs [prefix ot]
  (when (map? ot)
    (mapcat (fn [[k v]]
              (if (map? v)
                (inline-output-type-refs (str prefix ".fields." (name k)) v)
                [[(str prefix ".fields." (name k)) v ot]]))
            (:fields ot))))

(defn- walk-type-refs [spec]
  (concat
    (for [[ei ep] (map-indexed vector (:endpoints spec))
          :when (string? (:output_type ep))]
      [(str "endpoints[" ei "].output_type") (:output_type ep) ep])
    (mapcat (fn [[ei ep]]
              (inline-output-type-refs
                (str "endpoints[" ei "].output_type")
                (:output_type ep)))
            (map-indexed vector (:endpoints spec)))
    (for [[ei ep] (map-indexed vector (:endpoints spec))
          [k arg] (:args ep)
          :let [type-str (if (map? arg) (:type arg) arg)]
          :when (string? type-str)]
      [(str "endpoints[" ei "].args." (name k)) type-str ep])
    (for [[i it] (map-indexed vector (:input_types spec))
          [k v]  (:fields it)]
      [(str "input_types[" i "].fields." (name k)) v it])
    (mapcat (fn [[i ot]]
              (inline-output-type-refs (str "output_types[" i "]") ot))
            (map-indexed vector (:output_types spec)))))

(defn- check-type-refs [spec]
  (let [known (declared-type-names spec)]
    (for [[path type-str node] (walk-type-refs spec)
          :let [base (base-type-name type-str)]
          :when (and (seq base) (not (contains? known base)))]
      {:severity :error
       :path     path
       :message  (str "unknown type '" base "'")
       :node     node})))

(defn- all-nested-object-defs [prefix ot]
  (if (map? ot)
    (cons [prefix ot]
          (mapcat (fn [[k v]]
                    (all-nested-object-defs (str prefix ".fields." (name k)) v))
                  (:fields ot)))
    []))

(defn- all-type-defs [spec]
  (concat
    (for [[i it] (map-indexed vector (:input_types spec))]
      [(str "input_types[" i "]") it])
    (mapcat (fn [[i ot]]
              (all-nested-object-defs (str "output_types[" i "]") ot))
            (map-indexed vector (:output_types spec)))
    (mapcat (fn [[ei ep]]
              (all-nested-object-defs (str "endpoints[" ei "].output_type")
                                      (:output_type ep)))
            (map-indexed vector (:endpoints spec)))))

(defn- field-type [v] (if (map? v) (:name v) v))

(defn- merge-conflict-problem [path type-name field-name existing incoming node]
  {:severity :error
   :path     (str path ".fields." (name field-name))
   :message  (str "conflicting type for field '" (name field-name)
                  "' in '" type-name "': existing '" existing
                  "', new '" incoming "'")
   :node     node})

(defn- all-field-decls [spec]
  (for [[path def] (all-type-defs spec)
        [fk fv]    (:fields def)]
    {:type-name (:name def)
     :field     fk
     :type      (field-type fv)
     :path      path
     :def       def}))

(defn- check-type-merges [spec]
  (mapcat
    (fn [[[type-name field-name] decls]]
      (let [expected (:type (first decls))]
        (for [d (rest decls) :when (not= (:type d) expected)]
          (merge-conflict-problem (:path d) type-name field-name
                                  expected (:type d) (:def d)))))
    (group-by (juxt :type-name :field) (all-field-decls spec))))

(defn- structural-problems [spec]
  (when-let [errors (:errors (m/explain spec-schema spec))]
    (mapv #(explain-error->problem spec %) errors)))

(defn- cross-ref-problems [spec]
  (concat
    (check-duplicate-step-ids spec)
    (check-deps spec)
    (check-step-refs spec)
    (check-arg-refs spec)
    (check-type-refs spec)
    (check-type-merges spec)))

(defn lint-spec
  [spec]
  (if-let [problems (seq (structural-problems spec))]
    (vec problems)
    (vec (cross-ref-problems spec))))
