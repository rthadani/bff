(ns bff.validator)

(defprotocol BffValidator
  (validate [this args ctx]))

;; Plain fns satisfy BffValidator via IFn.
(extend-protocol BffValidator
  clojure.lang.IFn
  (validate [f args ctx] (f args ctx)))

(defonce ^:private validator-registry (atom {}))

(defn register-validator!
  "Register a BffValidator (or plain fn) under key k."
  [k validator]
  (swap! validator-registry assoc k validator))

(defn- resolve-validator [validator-cfg]
  (if-let [k (:key validator-cfg)]
    (or (get @validator-registry k)
        (throw (ex-info (str "No validator registered for key: " k)
                        {:key k :registered (keys @validator-registry)})))
    (requiring-resolve (symbol (:ns validator-cfg) (:fn validator-cfg)))))

(defn- validate-arg [arg-name value rules]
  (when (some? value)
    (let [failed? (or (and (:pattern rules)
                           (string? value)
                           (not (re-matches (re-pattern (:pattern rules)) value)))
                      (and (:min rules) (number? value) (< value (:min rules)))
                      (and (:max rules) (number? value) (> value (:max rules))))]
      (when failed?
        {:message (or (:message rules)
                      (str (name arg-name) " failed validation"))}))))

(defn- run-builtin-validation [endpoint args]
  (->> (:args endpoint {})
       (keep (fn [[arg-name arg-spec]]
               (when-let [rules (:validate arg-spec)]
                 (validate-arg arg-name (get args arg-name) rules))))))

(defn run-validation
  "Run all declared validators for an endpoint against the provided args.
   Returns a seq of {:message ...} error maps, or nil if everything passes.
   Runs built-in arg rules first, then the custom validator if declared."
  [endpoint args ctx]
  (let [builtin (run-builtin-validation endpoint args)
        custom  (when-let [vcfg (:validator endpoint)]
                  (validate (resolve-validator vcfg) args ctx))]
    (not-empty (concat builtin custom))))
