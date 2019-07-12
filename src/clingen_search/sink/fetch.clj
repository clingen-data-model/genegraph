;; Automatically retrieve data from remote sources for building knowledge base
(ns clingen-search.sink.fetch
  (:require [miner.ftp :as ftp]
            [clojure.java.io :as io]
            [clj-http.client :as http]
            [clojure.string :as string]
            [io.pedestal.log :as log])
  (:import [java.util.zip GZIPInputStream ZipInputStream ZipEntry]))

(def zip-exts [".zip" ".gz"])

(defn compressed? 
  "Return true if target file has been compressed"
  [target-file]
  (some #(string/includes? target-file %) zip-exts))

(defn unzip-target
  "Unzip downloaded file if it is compressed; save in same location sans
  extension"
  [file-name]
  (when (compressed? file-name)
    (let [[_ dest-file ext] (re-find #"^(.+)(\..+)$" file-name)]
      (with-open [in-s (io/input-stream file-name)
                  unzip-s (if (= ".zip" ext) (ZipInputStream. in-s)
                              (GZIPInputStream. in-s))
                  w (io/output-stream dest-file)]
        (when (= ".zip" ext) (.getNextEntry unzip-s))
        (io/copy unzip-s w)))))

(defn get-ftp
  [url target-file & opts]
  (let [dir (re-find #"^.*/" (.getPath url))
        remote-file (re-find #"[^/]+$" (.getPath url))
        con-str (str "ftp://anonymous:user%40example.com@" (.getHost url) dir)]
    (ftp/with-ftp [client con-str :file-type :binary]
      (ftp/client-get client remote-file target-file))
    (unzip-target target-file)))

(defn get-http
  "Retrieve, store, and (if necessary) decompress location at url and store 
  at target-file "
  [url target-file opts]
  (if-let [result (:body (http/get url (into {:as :byte-array} opts)))]
    (do (with-open [f (io/output-stream target-file)]
          (.write f result))
        (unzip-target target-file))
    (log/warn :fn :get-http :msg :cant-retrieve :url url)))

(defn fetch-data
  "retrieve file from remote url and store in data directory
  used to stage imports from external sources. Currently supports only
  http and ftp"
  [url-str target-file opts]
  (log/info :fn :fetch-data :msg :retrieving :url url-str)
  (if-let [url (io/as-url url-str)]
    (cond 
      (= "http" (.getProtocol url)) (get-http url-str target-file opts)
      (= "https" (.getProtocol url)) (get-http url-str target-file opts)
      (= "ftp" (.getProtocol url)) (get-ftp url target-file opts)
      :default (log/error :fn :fetch-data :msg :invalid-protocol :url url-str))))

(defn fetch-all-remote-assets
  [remote-assets]
  (doseq [asset remote-assets]
         (apply fetch-data asset)))
