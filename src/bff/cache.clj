(ns bff.cache
  (:require [taoensso.timbre :as log]))

(defprotocol CacheStore
  (cache-get       [this key])
  (cache-put       [this key value ttl-ms])
  (cache-invalidate [this key]))

;; Java implementations of io.github.rthadani.bff.CacheStore are first-class.
;; The cache passes opaque values through without conversion — the impl decides
;; how to serialize.
(extend-type io.github.rthadani.bff.CacheStore
  CacheStore
  (cache-get        [this key]           (.get this key))
  (cache-put        [this key value ttl] (.put this key value ttl))
  (cache-invalidate [this key]           (.invalidate this key)))

(defn lookup
  "Return the cached value for `key` from `store`, or nil if absent, expired,
   or if `store` is nil. Never throws — exceptions are logged and swallowed."
  [store key]
  (when store
    (try
      (cache-get store key)
      (catch Exception e
        (log/warnf "Cache get error for key=%s: %s" key (.getMessage e))
        nil))))

(defn save
  "Cache `value` under `key` in `store` with `ttl-ms` time-to-live. No-op
   when `store` is nil. Never throws."
  [store key value ttl-ms]
  (when store
    (try
      (cache-put store key value ttl-ms)
      (catch Exception e
        (log/warnf "Cache put error for key=%s: %s" key (.getMessage e))))))

(defn invalidate
  "Remove `key` from `store`. No-op when `store` is nil. Never throws."
  [store key]
  (when store
    (try
      (cache-invalidate store key)
      (catch Exception e
        (log/warnf "Cache invalidate error for key=%s: %s" key (.getMessage e))))))
