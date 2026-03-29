(ns bff.spec-loader
  (:require [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [bff.jq-engine :as jq]))

(defn load-spec
  [resource-path]
  (if-let [r (io/resource resource-path)]
    (-> r slurp yaml/parse-string)
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
  {:endpoints   (vec (mapcat :endpoints specs))
   :input_types (vec (mapcat #(get % :input_types []) specs))})

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
