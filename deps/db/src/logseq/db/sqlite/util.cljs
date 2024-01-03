(ns logseq.db.sqlite.util
  "Utils fns for backend sqlite db"
  (:require [cljs-time.coerce :as tc]
            [cljs-time.core :as t]
            [clojure.string :as string]
            [logseq.db.frontend.schema :as db-schema]
            [logseq.common.util :as common-util]))

(defonce db-version-prefix "logseq_db_")
(defonce file-version-prefix "logseq_local_")

(defn db-based-graph?
  [graph-name]
  (string/starts-with? graph-name db-version-prefix))

(defn local-file-based-graph?
  [s]
  (and (string? s)
       (string/starts-with? s file-version-prefix)))

(defn get-schema
  "Returns schema for given repo"
  [repo]
  (if (db-based-graph? repo)
    db-schema/schema-for-db-based-graph
    db-schema/schema))

(defn time-ms
  "Copy of util/time-ms. Too basic to couple this to main app"
  []
  (tc/to-long (t/now)))

(defn block-with-timestamps
  "Adds updated-at timestamp and created-at if it doesn't exist"
  [block]
  (let [updated-at (time-ms)
        block (cond->
               (assoc block :block/updated-at updated-at)
                (nil? (:block/created-at block))
                (assoc :block/created-at updated-at))]
    block))

(def sanitize-page-name common-util/page-name-sanity-lc)

(defn build-new-property
  "Build a standard new property so that it is is consistent across contexts"
  [block]
  (block-with-timestamps
   (merge {:block/type "property"
           :block/journal? false
           :block/format :markdown}
          block)))
