(ns bff.linter
  (:require [malli.core :as m]))

(def ^:private problem-schema
  [:map
   [:severity [:enum :error :warning]]
   [:path     :string]
   [:message  :string]
   [:node     :any]])

(defn problem?
  [x]
  (m/validate problem-schema x))

(defn lint-spec
  [_spec]
  [])
