(ns bff.validator
  (:require [bff.interop :as interop]
            [bff.registry :as registry]))

(defprotocol BffValidator
  (validate [this args ctx]))

;; Plain fns satisfy BffValidator via IFn.
(extend-protocol BffValidator
  clojure.lang.IFn
  (validate [f args ctx] (f args ctx)))

;; Java implementations of io.github.rthadani.bff.BffValidator are first-class.
;; Args/ctx are converted to String-keyed java.util.Map on the way in;
;; returned error maps are keywordized on the way out.
(extend-type io.github.rthadani.bff.BffValidator
  BffValidator
  (validate [this args ctx]
    (some-> (.validate this (interop/->java args) (interop/->java ctx))
            interop/->clj
            seq
            vec)))

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
  "Run built-in and custom validation for an endpoint.

   Returns a seq of {:message ...} error maps, or nil if everything passes.
   Built-in arg rules run first, then the custom :validator declared on the
   endpoint (looked up in `validators`).

   `validators` is the caller-owned registry map (\"key\" → impl); pass an
   empty map when no custom validators are configured."
  [endpoint args ctx validators]
  (let [builtin (run-builtin-validation endpoint args)
        custom  (when-let [vcfg (:validator endpoint)]
                  (validate (registry/resolve-impl vcfg validators "validator")
                            args ctx))]
    (not-empty (concat builtin custom))))
