(ns build
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib             'io.github.rthadani/bff)
(def default-version "0.1.0-SNAPSHOT")

(def class-dir "target/classes")
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn- jar-file [version]
  (format "target/%s-%s.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [{:keys [version] :or {version default-version} :as opts}]
  (b/write-pom {:class-dir class-dir
                :lib       lib
                :version   version
                :basis     @basis
                :src-dirs  ["src"]
                :pom-data  [[:description "Spec-driven GraphQL BFF engine for Clojure"]
                            [:url "https://github.com/rthadani/bff"]
                            [:licenses
                             [:license
                              [:name "MIT License"]
                              [:url "https://opensource.org/licenses/MIT"]]]]})
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
