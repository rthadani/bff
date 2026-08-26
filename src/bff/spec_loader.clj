(ns bff.spec-loader
  (:require [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [bff.jq-engine :as jq]))

(defn- resolve-env-str
  [s]
  (str/replace s
               #"\$\{([^}:-]+)(?::-(.*?))?\}"
               (fn [[_ var-name default]]
                 (or (System/getenv var-name)
                     default
                     (throw (ex-info (str "Environment variable not set: " var-name)
                                     {:variable var-name}))))))

(defn- resolve-env-vars
  [x]
  (cond
    (string? x)     (resolve-env-str x)
    (map? x)        (update-vals x resolve-env-vars)
    (sequential? x) (mapv resolve-env-vars x)
    :else           x))

(defn load-spec
  [resource-path]
  (if-let [r (io/resource resource-path)]
    (-> r slurp yaml/parse-string resolve-env-vars)
    (throw (ex-info (str "Spec file not found: " resource-path)
                    {:path resource-path}))))

(defn- list-yaml-resources
  "List all .yaml/.yml resource paths in a classpath directory.
   Works for both file-system and JAR classpath entries."
  [dir-path]
  (when-let [url (io/resource dir-path)]
    (case (.getProtocol url)
      "file"
      (let [dir (java.io.File. (.toURI url))]
        (->> (.listFiles dir)
             (filter #(and (.isFile %)
                           (let [n (.getName %)]
                             (or (str/ends-with? n ".yaml")
                                 (str/ends-with? n ".yml")))))
             (map #(str dir-path (.getName %)))
             sort))

      "jar"
      (let [conn       (.openConnection url)
            jar        (.getJarFile conn)
            dir-prefix (-> (.getPath url) (str/split #"!" 2) second (subs 1))]
        (->> (enumeration-seq (.entries jar))
             (map #(.getName %))
             (filter #(and (str/starts-with? % dir-prefix)
                           (not= % dir-prefix)
                           (let [rel (subs % (count dir-prefix))]
                             (and (not (str/includes? rel "/"))
                                  (or (str/ends-with? rel ".yaml")
                                      (str/ends-with? rel ".yml"))))))
             sort))

      nil)))

(defn- merge-specs
  [specs]
  {:endpoints       (vec (mapcat :endpoints specs))
   :input_types     (vec (mapcat #(get % :input_types  []) specs))
   :output_types    (vec (mapcat #(get % :output_types []) specs))
   :scalars         (vec (mapcat #(get % :scalars      []) specs))
   :forward_headers (vec (distinct (mapcat #(get % :forward_headers []) specs)))})

(defn- compile-mapping-entry [mapping]
  (if-let [expr (:jq mapping)]
    (assoc mapping :compiled-jq (jq/compile-query expr))
    mapping))

(defn- compile-param-map [param-map]
  (when param-map
    (->> param-map
         (map (fn [[k v]] [k (compile-mapping-entry v)]))
         (into {}))))

(defn- compile-output-map [output-map]
  (when output-map
    (->> output-map
         (map (fn [[k v]]
                [k (if (:source v)
                     (compile-mapping-entry v)
                     (compile-output-map v))]))
         (into {}))))

(defn- compile-compensation [comp]
  (some-> comp
          (update :input_mapping compile-param-map)
          (update :body_mapping  compile-param-map)))

(defn- compile-step [step]
  (-> step
      (update :input_mapping compile-param-map)
      (update :body_mapping  compile-param-map)
      (update :compensation  compile-compensation)
      (cond-> (:foreach step) (update :foreach compile-mapping-entry))))

(defn- collect-step-ids
  "Recursively collect all step_id values referenced in a mapping subtree."
  [m]
  (cond
    (not (map? m)) #{}
    (:source m)    (let [src (:source m)]
                     (if (or (= src "step") (= src :step))
                       (when-let [sid (:step_id m)] #{sid})
                       #{}))
    :else          (into #{} (mapcat collect-step-ids (vals m)))))

(defn- build-step-output-fields
  "Returns a map of step-id (string) to the set of top-level output field
   names (keywords) that read from it. Steps not referenced by any field
   are absent from the map."
  [endpoint]
  (reduce
    (fn [acc [top-field field-def]]
      (reduce (fn [a sid]
                (update a sid (fnil conj #{}) (keyword top-field)))
              acc
              (collect-step-ids field-def)))
    {}
    (:output_mapping endpoint)))

(defn- preload-transformer! [transformer]
  (when (and transformer (:ns transformer))
    (require (symbol (:ns transformer)))))

(defn- compile-endpoint [endpoint]
  (preload-transformer! (:transformer endpoint))
  (-> endpoint
      (assoc :step-output-fields (build-step-output-fields endpoint))
      (update :backend_chain #(mapv compile-step %))
      (update :output_mapping compile-output-map)))

(defn compile-spec
  [spec]
  (update spec :endpoints #(mapv compile-endpoint %)))

(defn load-and-compile
  "Load and compile a BFF spec. `path` can be:
   - A single YAML resource path: \"bff-spec.yaml\"
   - A resource directory (trailing slash): \"specs/\"  → merges all .yaml/.yml files
   - A collection of resource paths: [\"users.yaml\" \"orders.yaml\"]"
  [path]
  (cond
    (sequential? path)
    (->> path (map load-spec) merge-specs compile-spec)

    (str/ends-with? path "/")
    (let [paths (list-yaml-resources path)]
      (when (empty? paths)
        (throw (ex-info (str "No YAML specs found in: " path) {:path path})))
      (->> paths (map load-spec) merge-specs compile-spec))

    :else
    (-> path load-spec compile-spec)))
