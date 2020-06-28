(defn genegraph.cache
  (:import (:import (org.rocksdb RocksDB RocksDBException Options))))

(defn init! [db-path]
  (let [opts (-> (Options.) (.setCreateIfMissing true))]
    (RocksDB/open opts db-path)))
