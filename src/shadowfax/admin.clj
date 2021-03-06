(ns shadowfax.admin
  (:require (shadowfax.libs.clojure-hbase [core :as hb]
                                          [util :as hu]
                                          [admin :as ha]))
  (:import (java.util Set)
           (org.apache.hadoop.hbase HBaseConfiguration
                                    HTableDescriptor
                                    HConstants
                                    KeyValue
                                    TableNotFoundException)
           (org.apache.hadoop.hbase.client HTable
                                           HTablePool
                                           HBaseAdmin
                                           RowLock
                                           Get Put Delete Scan Result)
           (org.apache.hadoop.hbase.util Bytes)
           (org.apache.hadoop.hbase.io.hfile Compression$Algorithm)
           (org.apache.hadoop.hbase.regionserver.compactions CompactionRequest$CompactionState)
           (org.apache.hadoop.hbase.regionserver StoreFile$BloomType))
  (:require (clojure [pprint :as p]))
  (:require (shadowfax.utils [seq :as seq])))

(def gz Compression$Algorithm/GZ)
(def lzo Compression$Algorithm/LZO)
(def snappy Compression$Algorithm/SNAPPY)
(def none Compression$Algorithm/NONE)

(def major CompactionRequest$CompactionState/MAJOR)
(def major-and-minor CompactionRequest$CompactionState/MAJOR_AND_MINOR)
(def minor CompactionRequest$CompactionState/MINOR)
(def no-compaction CompactionRequest$CompactionState/NONE)
(def compaction-states #{major major-and-minor minor})

;;; Bloom filter types
(def no-bloom StoreFile$BloomType/NONE)
(def row-bloom StoreFile$BloomType/ROW)
(def row-column-bloom StoreFile$BloomType/ROWCOL)

;;; Using HTable
;;; Use hb/default-config or hb/default-config*
(defn get-configuration
  [table-name]
  (let [conf (.getConfiguration (hb/table table-name))
        conf-iterator (.iterator conf)]
    (loop [entry (try
                   (.next conf-iterator)
                   (catch java.util.NoSuchElementException _ nil))
           result {}]
      (if entry
        (recur (try
                 (.next conf-iterator)
                 (catch java.util.NoSuchElementException _ nil))
               (assoc result (.getKey entry) (.getValue entry)))
        result))))

(defn get-configuration*
  [table-name]
  (let [conf (.getConfiguration (hb/table table-name))]
    (into {} conf)))

(defn get-configuration-obj
  [table-name]
  (.getConfiguration (hb/table table-name)))

;;;
(defn get-table-descriptor
  [table-name]
  (.getTableDescriptor (ha/hbase-admin) (Bytes/toBytes table-name)))

(defn get-column-family-details*
  [column-descriptor]
  (let [entries (seq (.getValues column-descriptor))
        details (into {}
                      (for [entry entries
                            :let [k (hu/as-str (.get (.getKey entry)))
                                  v (hu/as-str (.get (.getValue entry)))]]
                        [k v]))]
    (assoc details "NAME" (.getNameAsString column-descriptor))))

(defn get-column-family-details
  [column-descriptor]
  (let [ttl (.getTimeToLive column-descriptor)
        ttl-days (float (/ ttl 3600 24))]
    {:blocksize (.getBlocksize column-descriptor)
     :bloom-filter-type (.getBloomFilterType column-descriptor)
     :compaction-compression-type (.getCompactionCompressionType column-descriptor)
     :max-versions (.getMaxVersions column-descriptor)
     :min-versions (.getMinVersions column-descriptor)
     :name (.getNameAsString column-descriptor)
     :scope (.getScope column-descriptor)
     :ttl ttl
     :ttl-days ttl-days
     :block-cache-enabled? (.isBlockCacheEnabled column-descriptor)
     :in-memory? (.isInMemory column-descriptor)}))

;;; 
(defmulti get-table-details class)

(defmethod get-table-details HTableDescriptor
  [table-descriptor]
  (let [column-descriptors (.getFamilies table-descriptor)
        families (map #(.getNameAsString %) column-descriptors)
        max-file-size (.getMaxFileSize table-descriptor)
        mem-store-flush-size (.getMemStoreFlushSize table-descriptor)]
    {:table (.getNameAsString table-descriptor)
     :families families
     :families-details (map get-column-family-details column-descriptors)
     :max-file-size max-file-size
     :max-file-size-in-MB (float (/ max-file-size 1024 1024))
     :mem-store-flush-size mem-store-flush-size
     :mem-store-flush-size-in-MB (float (/ mem-store-flush-size 1024 1024))
     :owner-string (.getOwnerString table-descriptor)
     :region-split-policy-classname (.getRegionSplitPolicyClassName table-descriptor)
     :read-only? (.isReadOnly table-descriptor)}))

(defmethod get-table-details String
  [table-name]
  (try
    (let [table-descriptor (get-table-descriptor table-name)]
      (get-table-details table-descriptor))
    (catch TableNotFoundException _ :table-not-found)))

;;; 
(defn list-tables
  ([]
     (list-tables false))
  ([detailed?]
     (let [htable-descriptors (seq (.listTables (ha/hbase-admin)))]
       (if detailed?
         (map get-table-details htable-descriptors)
         (map #(.getNameAsString %) htable-descriptors))))
  ([pattern detailed?]
     (let [htable-descriptors (seq (.listTables (ha/hbase-admin) pattern))]
       (if detailed?
         (map get-table-details htable-descriptors)
         (map #(.getNameAsString %) htable-descriptors)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Stats/Status
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn server-load
  [cluster-status server & [regions-load?]]
  (let [load (.getLoad cluster-status server)
        server-load {:coprocessors (vec (.getRsCoprocessors load))
                     :load (.getLoad load)
                     :max-heap-MB (.getMaxHeapMB load)
                     :mem-store-size-in-MB (.getMemStoreSizeInMB load)
                     :number-of-regions (.getNumberOfRegions load)
                     :number-of-requests (.getNumberOfRequests load)
                     :storefile-index-size-in-MB (.getStorefileIndexSizeInMB load)
                     :storefiles (.getStorefiles load)
                     :storefile-size-in-MB (.getStorefileSizeInMB load)
                     :total-number-of-requests (.getTotalNumberOfRequests load)
                     :used-heap-MB (.getUsedHeapMB load)
                     :version (.getVersion load)
                     :summary (str load)
                     :server-info (str server)}]
    
    (if regions-load?
      (merge server-load {:regions-load (.getRegionsLoad load)})
      server-load)))

;;; (clojure.pprint/pprint (cluster-status :load-details? false))
(defn cluster-status
  [& {:keys [regions-load? load-details?] :or {load-details? true regions-load? false}}]
  (let [status (ha/cluster-status)
        servers (vec (.getServers status))
        load-details (when load-details? {:load-details (map #(server-load status % regions-load?) servers)})]
    (merge load-details
           {:average-load (.getAverageLoad status)
            :backup-masters (vec (.getBackupMasters status))
            :backup-masters-size (.getBackupMastersSize status)
            :cluster-id (.getClusterId status)
            :dead-server-names (vec (.getDeadServerNames status))
            :dead-servers (.getDeadServers status)
            :hbase-version (.getHBaseVersion status)
            :servers servers
            :master (str (.getMaster status))
            :master-coprocessors (vec (.getMasterCoprocessors status))
            :regions-count (.getRegionsCount status)
            :regions-in-transition (into {} (.getRegionsInTransition status))
            :requests-count (.getRequestsCount status)
            :servers-size (.getServersSize status)
            :version (.getVersion status)})))

;;;
(defn start-compression
  [table family-name & {:keys [compression-type] :or {compression-type snappy}}]
  (let [column-descriptor (ha/column-descriptor family-name
                                                :compression-type compression-type)]
    (ha/disable-table table)
    (ha/modify-column-family table column-descriptor)
    (ha/enable-table table)
    (ha/major-compact table)))

(defn disable-compression
  [table family-name]
  (let [column-descriptor (ha/column-descriptor family-name
                                                :compression-type none)]
    (ha/disable-table table)
    (ha/modify-column-family table column-descriptor)
    (ha/enable-table table)
    (ha/major-compact table)))

;;; Compaction
(defn get-compaction-state
  [table-name]
  (.getCompactionState @ha/*admin* (Bytes/toBytes table-name)))

(defn table-compacting?
  [table-name & {:keys [compaction-state] :or {compaction-state :any}}]
  (let [table-compaction-state (get-compaction-state table-name)]
    (if (= compaction-state :any)
      (boolean (compaction-states table-compaction-state))
      (= compaction-state table-compaction-state))))

(defn get-compacting-tables
  [tables & {:keys [compaction-state] :or {compaction-state :any}}]
  (doall (filter #(table-compacting? % :compaction-state compaction-state) tables)))

(defn get-all-compacting-tables
  [& {:keys [compaction-state] :or {compaction-state :any}}]
  (let [tables (list-tables)]
    (get-compacting-tables tables :compaction-state compaction-state)))

;;;
(defn compression-enabled?
  [table-name column-family]
  (let [table-descriptor (.getTableDescriptor @ha/*admin* (Bytes/toBytes table-name))
        column-descriptors (.getFamilies table-descriptor)]
    (if-let [column-descriptor (seq/find-first #(= (hu/as-str (.getName %)) column-family) column-descriptors)]
      (not= (.getCompression column-descriptor) none)
      false)))

(defn get-column-families
  [table-name]
  (let [table-descriptor (.getTableDescriptor @ha/*admin* (Bytes/toBytes table-name))
        column-descriptors (.getFamilies table-descriptor)]
    (map #(hu/as-str (.getName %)) column-descriptors)))

(defn find-tables-with-column-family
  ([column-family]
     (find-tables-with-column-family (list-tables) column-family))
  ([tables column-family]
     (doall
      (filter (fn [table]
                (seq/include? column-family (get-column-families table)))
              tables))))

(defn find-tables-with-no-compression
  ([column-family]
     (let [tables (find-tables-with-column-family column-family)]
       (remove #(compression-enabled? % column-family) tables)))
  ([tables column-family]
     (let [tables (find-tables-with-column-family tables column-family)]
       (remove #(compression-enabled? % column-family) tables))))

;;;
(defn region-count
  [tables]
  (apply + (map #(count (.getTableRegions (deref ha/*admin*) (Bytes/toBytes %))) tables)))

;;; TODOS
(defn get-alter-status
  "HBaseAdmin"
  [])

;;; 
(comment
  (get-all-compacting-tables :compaction-state a/major))

(comment
  (use 'hbase.ss.init)
  (with-prod
    (hb/with-table [table (hb/table "table")]
      (.getTableName table))))
