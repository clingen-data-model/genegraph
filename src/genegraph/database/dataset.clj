(ns genegraph.database.dataset
  "Namespace for handling operations on persistent Jena datasets.
  Specifically designed around handling asychronous writes. "
  (:require [clojure.spec.alpha :as spec]
            [clojure.core.async :as async])
  (:import [org.apache.jena.tdb2 TDB2Factory]
           [org.apache.jena.query Dataset ReadWrite]
           [java.util.concurrent BlockingQueue ArrayBlockingQueue TimeUnit]
           [java.util List ArrayList]
           [org.apache.jena.query.text TextDatasetFactory]))


(defrecord PersistentDataset [dataset
                              run-atom
                              complete-promise
                              write-queue
                              write-queue-size
                              name]
  
  java.io.Closeable
  (close [this]
    (reset! run-atom false)
    (if (deref complete-promise (* 5 1000) false)
      (.close dataset)
      (do (throw (ex-info "Timeout closing dataset."
                          (select-keys this [:name])))
          false))))


(defn execute
  "Execute command on dataset"
  [dataset command]
  ;; consider raising exception if bad command
  (case (:command command)
    :replace (.replaceNamedModel dataset
                                 (:model-name command)
                                 (:model command))
    :remove (.removeNamedModel dataset
                               (:model-name command))
    (throw (ex-info "Invalid command" command))))

(defn deliver-commit-promise
  "Deliver promise when command is committed to the database."
  [command]
  (when-let [committed-promise (:committed command)]
    (deliver committed-promise true)))

(defn write-loop
  "Read commands from queue and execute them on dataset. Pulls multiple records
  from the queue if possible given buffer limit and availability"
  [{:keys [write-queue-size write-queue run-atom complete-promise dataset] :as dataset-record}]
  (let [write-buffer (ArrayList. write-queue-size)]
    (while @run-atom
      (try
        (when-let [first-command (.poll write-queue 1000 TimeUnit/MILLISECONDS)]
          (.drainTo write-queue write-buffer write-queue-size)
          (let [commands (cons first-command write-buffer)]
            (.begin dataset ReadWrite/WRITE)
            (run! #(execute dataset %) commands)
            (.clear write-buffer)
            (.commit dataset)
            (.end dataset)
            (run! deliver-commit-promise commands)))
        (catch Exception e (println e))))
    (deliver complete-promise true)))

(defn execute-async
  "execute command list asynchronously"
  [{:keys [run-atom write-queue] :as dataset} commands]
  (when @run-atom
    (run! #(.put write-queue %) commands)))

(defn execute-sync
  "Execute command list synchronously. Will claim write transaction."
  [{:keys [dataset]} commands]
  (.begin dataset ReadWrite/WRITE)
  (run! #(execute dataset %) commands)
  (.commit dataset)
  (.end dataset))

(defn dataset [opts]
  (let [write-queue-size (or (:write-queue-size opts) 100)
        persistent-dataset (map->PersistentDataset
                            (merge
                             opts
                             {:dataset (cond
                                         (:assembly-path opts)
                                         (TextDatasetFactory/create (:assembly-path))
                                         (:path opts)
                                         (TDB2Factory/connectDataset (:path opts))
                                         :else (TDB2Factory/createDataset))
                              :run-atom (atom true)
                              :complete-promise (promise)
                              :write-queue-size write-queue-size
                              :write-queue (ArrayBlockingQueue.
                                            write-queue-size)}))]
    (.start (Thread. #(write-loop persistent-dataset)))
    persistent-dataset))
