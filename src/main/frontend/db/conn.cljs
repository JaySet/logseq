(ns frontend.db.conn
  "Contains db connections."
  (:require [clojure.string :as string]
            [frontend.util :as util]
            [frontend.mobile.util :as mobile-util]
            [frontend.state :as state]
            [frontend.config :as config]
            [frontend.util.text :as text-util]
            [logseq.graph-parser.text :as text]
            [logseq.db :as ldb]
            [logseq.db.frontend.schema :as db-schema]
            [logseq.common.util :as common-util]
            [datascript.core :as d]
            [logseq.db.sqlite.util :as sqlite-util]))

(defonce conns (atom {}))

(defn get-repo-path
  [url]
  (assert (string? url) (str "url is not a string: " (type url)))
  url)

(defn get-repo-name
  [repo-url]
  (cond
    (mobile-util/native-platform?)
    (text-util/get-graph-name-from-path repo-url)

    (config/local-file-based-graph? repo-url)
    (config/get-local-dir repo-url)

    :else
    (get-repo-path repo-url)))

(defn get-short-repo-name
  "repo-name: from get-repo-name. Dir/Name => Name"
  [repo-name]
  (let [repo-name' (cond
                     (util/electron?)
                     (text/get-file-basename repo-name)

                     (mobile-util/native-platform?)
                     (common-util/safe-decode-uri-component (text/get-file-basename repo-name))

                     :else
                     repo-name)]
    (if (config/db-based-graph? repo-name')
      (string/replace-first repo-name' config/db-version-prefix "")
      repo-name')))

(defn datascript-db
  [repo]
  (when repo
    (let [path (get-repo-path repo)]
      (str (if (util/electron?) "" config/idb-db-prefix)
           path))))

(def get-schema sqlite-util/get-schema)

(defn get-db
  ([]
   (get-db (state/get-current-repo) true))
  ([repo-or-deref?]
   (if (boolean? repo-or-deref?)
     (get-db (state/get-current-repo) repo-or-deref?)
     (get-db repo-or-deref? true)))
  ([repo deref?]
   (let [repo (if repo repo (state/get-current-repo))]
     (when-let [conn (get @conns (datascript-db repo))]
       (if deref?
         @conn
         conn)))))

(defn remove-conn!
  [repo]
  (swap! conns dissoc (datascript-db repo)))

(defn kv
  [key value & {:keys [id]}]
  {:db/id (or id -1)
   :db/ident key
   key value})

(defn transact!
  ([repo tx-data]
   (transact! repo tx-data nil))
  ([repo tx-data tx-meta]
   (when-let [conn (get-db repo false)]
     ;; (prn :debug "DB transact:")
     ;; (frontend.util/pprint {:tx-data tx-data
     ;;                        :tx-meta tx-meta})
     (if tx-meta
       (d/transact! conn (vec tx-data) tx-meta)
       (d/transact! conn (vec tx-data))))))

(defn start!
  ([repo]
   (start! repo {}))
  ([repo {:keys [listen-handler db-graph?]}]
   (let [db-name (datascript-db repo)
         db-conn (ldb/start-conn :schema (get-schema repo) :create-default-pages? false)]
     (swap! conns assoc db-name db-conn)
     (when listen-handler
       (listen-handler repo))
     (when db-graph?
       (transact! db-name [(kv :db/type "db")])
       (transact! db-name [(kv :schema/version db-schema/version)]))
     (ldb/create-default-pages! db-conn {:db-graph? db-graph?}))))

(defn destroy-all!
  []
  (reset! conns {}))
