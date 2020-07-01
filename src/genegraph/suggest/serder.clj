(ns genegraph.suggest.serder)

(defn serializable? [v]
  (instance? java.io.Serializable v))

(defn serialize 
  "Serializes value, returns a byte array"
  [v]
  (let [buff (java.io.ByteArrayOutputStream. 1024)]
    (with-open [dos (java.io.ObjectOutputStream. buff)]
      (.writeObject dos v))
    (.toByteArray buff)))

(defn deserialize 
  "Accepts a byte array, returns deserialized value"
  [bytes]
  (with-open [dis (java.io.ObjectInputStream.
                   (java.io.ByteArrayInputStream. bytes))]
    (.readObject dis)))
