(ns genegraph.source.registry.prefixable-registry)

(defprotocol Registry
  "A mutable set of associations between keys and values. All keys
   must be serialized in a way that they can be retrieved by a prefix.
   As in, the serialization of the keys 'abc12' and 'abc34' share a leading
   prefix of bytes with each other and with the serialization of 'abc'"
  (get-key [this k])
  (put-key! [this k v])
  (delete-key! [this k]))

(defprotocol RegistryPrefixability
  (prefixable-serialization [this k]
    "Return a serialization of k which is prefixable.
     As in, the serialization of 'abc1' and 'abc2' have the same leading bytes
     as the serialization of 'abc'.
     Any k for which this cannot be guaranteed should throw an exception.")
  (prefix-iterator [this prefix]
    "Return a java.util.Iterator over all the entries whose keys begin with prefix.
     Uses prefixable-serialization to serialize the prefix. Assumes the keys
     in the registry were put with the prefixable-registry protocol."))



#_(defprotocol PrefixableSerialization
    (prefixable-serialization [value]
      "Return a serialization of k which is prefixable.
     As in, the serialization of 'abc1' and 'abc2' have the same leading bytes
     as the serialization of 'abc'.
     Any k for which this cannot be guaranteed should throw an exception."))
