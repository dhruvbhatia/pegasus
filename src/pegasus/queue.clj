(ns pegasus.queue
  "Enqueue and dequeue operations"
  (:require [clj-http.client :as client]
            [clojure.core.async :as async]
            [durable-queue :refer :all]
            [org.bovinegenius.exploding-fish :as uri]
            [clojure.core.cache :as cache]
            [pegasus.state :as state]
            [taoensso.timbre :as timbre
             :refer (log  trace  debug  info  warn  error  fatal  report
                          logf tracef debugf infof warnf errorf fatalf reportf
                          spy get-env log-env)]))

(defn handle-robots-url
  [url]
  (let [robots-payload (try
                         (:body
                          (client/get url
                                      {:socket-timeout 1000
                                       :conn-timeout 1000
                                       :headers
                                       {"User-Agent"
                                        (:user-agent state/config)}}))
                         (catch Exception e nil))

        robots-cache (:robots-cache state/config)
        
        host (uri/host url)]
    
    (if robots-payload
      (cache/miss robots-cache
                  host
                  robots-payload)
      (cache/miss robots-cache
                  host
                  "User-Agent *\nAllow /"))))

(defn setup-queue-worker
  [q q-name]
  (info :setting-up-q-worker)
  (let [default-delay (:min-delay-ms state/config)
        init-chan (:init-chan state/config)]
    (async/go-loop []

      ;; a timeout first
      (async/<!
       (async/timeout
        (+ default-delay
           (rand-int 1000))))

      (info :default-delay default-delay)
      
      ;; draw a url and pass
      ;; it through the pipeline
      (info q-name)
      (let [task (take! q q-name 10 nil)]
        (when-not (nil? task)
          (let [url (deref task)]
            (info :obtained url)
            (if (= (uri/path url) "/robots.txt")
              (handle-robots-url url)
              (async/>! init-chan {:input url
                                   :config state/config}))
            (complete! task))))
      
      (when-not (:stop?
                 @(:state state/config))
       (recur)))))

(defn enqueue-robots
  [a-url q visited-hosts-cache]
  (let [q-name (-> a-url uri/host keyword)
        robots-url (uri/resolve-uri a-url "/robots.txt")]
    (put! q q-name robots-url)
    (cache/miss visited-hosts-cache
                (uri/host a-url)
                "1")))

(defn enqueue-url
  [url]
  (let [q-name (-> url uri/host keyword)
        struct-dir (:struct-dir state/config)
        q (:queue state/config)
        visited-hosts (:hosts-visited-cache state/config)
        to-visit-cache (:to-visit-cache state/config)]

    ;; before we first hit a host, we need to confirm
    ;; that robots.txt has been obtained.
    ;; if not, we enqueue robots.txt and start monitoring
    ;; that queue.
    (info :enqueue url)
    (when-not (cache/lookup visited-hosts
                            (uri/host url))
      (do (debug :here? url)
          (enqueue-robots url q visited-hosts))
      
      (do (debug :here-2? url)
          (setup-queue-worker q q-name)))
    
    ;; insert this URL.
    (put! q q-name url)
    (cache/miss to-visit-cache
                url
                "1")))

(defn enqueue-pipeline
  "A pipeline component that consumes urls
  from the supplied object, enqueues them
  and continues."
  [obj]
  (let [extracted-uris (:extracted obj)]
    (doseq [uri extracted-uris]
      (enqueue-url uri))
    obj))

(defn build-queue-config
  [config]
  (let [struct-dir (:struct-dir config)
        q (queues struct-dir {:slab-size 1024
                              :fsync-take? true})]
    (merge config {:queue q})))

(defn global-to-visit
  "How many items are enqueued?"
  [q]
  (let [global-stats (stats q)]
    (reduce
     (fn [n [name named-q-stats]]
       (+ n (:enqueued named-q-stats)))
     0
     global-stats)))
