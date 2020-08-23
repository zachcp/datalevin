(ns ^:no-doc datalevin.replication
  (:import [com.alipay.sofa.jraft JRaftUtils Closure Status]
           [com.alipay.sofa.jraft.util Endpoint]
           [com.alipay.sofa.jraft.entity PeerId Task]
           [com.alipay.sofa.jraft.conf Configuration]
           ))

(def conf (JRaftUtils/getConfiguration "localhost:8081,localhost:8082,localhost:8083"))
