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

(defn lint-spec
  [spec]
  (if (m/validate spec-schema spec)
    []
    [{:severity :error
      :path     ""
      :message  "spec does not match the structural schema"
      :node     spec}]))
