(ns bff.cache
  (:require [taoensso.timbre :as log]))

(defprotocol CacheStore
  (cache-get       [this key])
  (cache-put       [this key value ttl-ms])
  (cache-invalidate [this key]))

(defonce ^:private store (atom nil))

(defn register-cache! [impl]
  (reset! store impl))

(defn lookup [key]
  (when-let [s @store]
    (try
      (cache-get s key)
      (catch Exception e
        (log/warnf "Cache get error for key=%s: %s" key (.getMessage e))
        nil))))

(defn save [key value ttl-ms]
  (when-let [s @store]
    (try
      (cache-put s key value ttl-ms)
      (catch Exception e
        (log/warnf "Cache put error for key=%s: %s" key (.getMessage e))))))

(defn invalidate [key]
  (when-let [s @store]
    (try
      (cache-invalidate s key)
      (catch Exception e
        (log/warnf "Cache invalidate error for key=%s: %s" key (.getMessage e))))))
