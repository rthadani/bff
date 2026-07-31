(ns bff.registry
  "Impl-resolution helper shared across every extension point.

   A spec references an extension in one of two forms:

     {:key \"my-fn\"}                → look up in the caller's registry map
     {:ns \"my.ns\" :fn \"my-fn\"}    → resolve a Clojure var by symbol

   Both forms return the extension implementation; the caller invokes it
   with whatever arity is appropriate for the extension type.")

(defn resolve-impl
  "Resolve an extension config to its implementation.

   `cfg`      — {:key ...} or {:ns ... :fn ...} from a step / endpoint spec
   `registry` — the caller's registry map, e.g. the config's :validators map
   `kind`     — extension kind name, used only for the error message
                (e.g. \"validator\", \"transformer\", \"resolver\")

   Throws ex-info when :key is present but missing from the registry, or
   when :ns/:fn resolves to nothing."
  [cfg registry kind]
  (if-let [k (:key cfg)]
    (or (get registry k)
        (throw (ex-info (str "No " kind " registered for key: " k)
                        {:key k :registered (keys registry)})))
    (if-let [ns-sym (:ns cfg)]
      (or (requiring-resolve (symbol ns-sym (:fn cfg)))
          (throw (ex-info (str "No " kind " var at " ns-sym "/" (:fn cfg))
                          {:ns ns-sym :fn (:fn cfg)})))
      (throw (ex-info (str kind " config must have :key or :ns/:fn")
                      {:cfg cfg})))))
