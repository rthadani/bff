(ns build
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib             'io.github.rthadani/bff)
(def default-version "0.1.0-SNAPSHOT")

(def class-dir "target/classes")
(def basis     (delay (b/create-basis {:project "deps.edn"})))

(defn- jar-file [version]
  (format "target/%s-%s.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn compile-java
  "Compile Java sources under java/ to target/classes.

   Required before running the Clojure test suite: bff.validator, bff.executor
   and bff.cache extend their protocols to the Java interfaces in
   io.github.rthadani.bff.*, and those classes must be on the classpath before
   the namespaces are loaded.

   Usage:  clj -T:build compile-java"
  [_]
  (b/javac {:src-dirs   ["java"]
            :class-dir  class-dir
            :basis      @basis
            :javac-opts ["-source" "17" "-target" "17"
                         "-Xlint:-options"]}))

(defn jar [{:keys [version] :or {version default-version} :as opts}]
  (b/write-pom {:class-dir class-dir
                :lib       lib
                :version   version
                :basis     @basis
                :src-dirs  ["src"]
                :pom-data  [[:description "Spec-driven GraphQL API fronting real REST backends"]
                            [:url "https://github.com/rthadani/bff"]
                            [:licenses
                             [:license
                              [:name "MIT License"]
                              [:url "https://opensource.org/licenses/MIT"]]]]})
  (compile-java opts)
  (b/compile-clj {:src-dirs   ["src"]
                  :class-dir  class-dir
                  :basis      @basis
                  :ns-compile '[bff.executor bff.validator bff.cache]})
  (b/copy-dir {:src-dirs   ["src"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file  (jar-file version)})
  opts)

(defn install [{:keys [version] :or {version default-version} :as opts}]
  (jar opts)
  (b/install {:basis     @basis
              :lib       lib
              :version   version
              :jar-file  (jar-file version)
              :class-dir class-dir}))

(defn deploy [{:keys [version] :or {version default-version} :as opts}]
  (jar opts)
  (dd/deploy {:installer :remote
              :artifact  (jar-file version)
              :pom-file  (b/pom-path {:lib       lib
                                      :class-dir class-dir})}))
