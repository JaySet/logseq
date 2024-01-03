(ns frontend.worker.util
  "Worker utils"
  (:refer-clojure :exclude [format])
  (:require [clojure.string :as string]
            ["remove-accents" :as removeAccents]
            [medley.core :as medley]
            [logseq.common.util :as common-util]
            [goog.string :as gstring]
            [clojure.core.async :as async]
            [cljs.core.async.impl.channels :refer [ManyToManyChannel]]
            [cljs-time.coerce :as tc]
            [cljs-time.core :as t]
            [cljs-bean.core :as bean]))

(defn search-normalize
     "Normalize string for searching (loose)"
     [s remove-accents?]
     (when s
       (let [normalize-str (.normalize (string/lower-case s) "NFKC")]
         (if remove-accents?
           (removeAccents normalize-str)
           normalize-str))))

(def safe-re-find common-util/safe-re-find)

(def uuid-string? common-util/uuid-string?)

(def page-name-sanity-lc
  "Delegate to common-util to loosely couple app usages to graph-parser"
  common-util/page-name-sanity-lc)

(defn safe-page-name-sanity-lc
  [s]
  (if (string? s)
    (page-name-sanity-lc s) s))

(defn distinct-by
  [f col]
  (medley/distinct-by f (seq col)))

(defn format
  [fmt & args]
  (apply gstring/format fmt args))

(defn remove-first [pred coll]
  ((fn inner [coll]
     (lazy-seq
      (when-let [[x & xs] (seq coll)]
        (if (pred x)
          xs
          (cons x (inner xs))))))
   coll))

(defn drain-chan
  "drop all stuffs in CH, and return all of them"
  [ch]
  (->> (repeatedly #(async/poll! ch))
       (take-while identity)))

(defn <ratelimit
 "return a channel CH,
  ratelimit flush items in in-ch every max-duration(ms),
  opts:
  - :filter-fn filter item before putting items into returned CH, (filter-fn item)
               will poll it when its return value is channel,
  - :flush-fn exec flush-fn when time to flush, (flush-fn item-coll)
  - :stop-ch stop go-loop when stop-ch closed
  - :distinct-key-fn distinct coll when put into CH
  - :chan-buffer buffer of return CH, default use (async/chan 1000)
  - :flush-now-ch flush the content in the queue immediately
  - :refresh-timeout-ch refresh (timeout max-duration)"
     [in-ch max-duration & {:keys [filter-fn flush-fn stop-ch distinct-key-fn chan-buffer flush-now-ch refresh-timeout-ch]}]
     (let [ch (if chan-buffer (async/chan chan-buffer) (async/chan 1000))
           stop-ch* (or stop-ch (async/chan))
           flush-now-ch* (or flush-now-ch (async/chan))
           refresh-timeout-ch* (or refresh-timeout-ch (async/chan))]
       (async/go-loop [timeout-ch (async/timeout max-duration) coll []]
         (let [{:keys [refresh-timeout timeout e stop flush-now]}
               (async/alt! refresh-timeout-ch* {:refresh-timeout true}
                           timeout-ch {:timeout true}
                           in-ch ([e] {:e e})
                           stop-ch* {:stop true}
                           flush-now-ch* {:flush-now true})]
           (cond
             refresh-timeout
             (recur (async/timeout max-duration) coll)

             (or flush-now timeout)
             (do (async/onto-chan! ch coll false)
                 (flush-fn coll)
                 (drain-chan flush-now-ch*)
                 (recur (async/timeout max-duration) []))

             (some? e)
             (let [filter-v (filter-fn e)
                   filter-v* (if (instance? ManyToManyChannel filter-v)
                               (async/<! filter-v)
                               filter-v)]
               (if filter-v*
                 (recur timeout-ch (cond->> (conj coll e)
                                     distinct-key-fn (distinct-by distinct-key-fn)
                                     true vec))
                 (recur timeout-ch coll)))

             (or stop
                 ;; got nil from in-ch, means in-ch is closed
                 ;; so we stop the whole go-loop
                 (nil? e))
             (async/close! ch))))
       ch))

(defn time-ms
  []
  (tc/to-long (t/now)))

(defn post-message
  [type data]
  (.postMessage js/self (bean/->js [type data])))

(defn concat-without-nil
  [& cols]
  (->> (apply concat cols)
       (remove nil?)))
