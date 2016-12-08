(ns funcatron.tron.modes.tron-mode
  (:require [funcatron.tron.util :as fu]
            [clojure.java.io :as cio]
            [taoensso.timbre
             :refer [log trace debug info warn error fatal report
                     logf tracef debugf infof warnf errorf fatalf reportf
                     spy get-env]]
            [ring.middleware.json :as rm-json :refer [wrap-json-response]]
            [compojure.core :refer [GET defroutes POST routes]]
            [compojure.route :refer [not-found resources]]
            [funcatron.tron.modes.common :as common]
            [funcatron.tron.brokers.shared :as shared-b]
            [funcatron.tron.routers.jar-router :as jarjar]
            [funcatron.tron.options :as opts])
  (:import (java.io File)
           (funcatron.abstractions MessageBroker MessageBroker$ReceivedMessage Lifecycle)
           (java.net URLEncoder)
           (com.fasterxml.jackson.databind ObjectMapper)))


(set! *warn-on-reflection* true)

(def ^:private file-version (fu/random-uuid))

(defn- clean-network
  "Remove everything from the network that's old"
  [state]
  (let [too-old (- (System/currentTimeMillis) (* 1000 60 3))] ;; if we haven't seen a node in 3 minutes, clean it
    (swap!
      (::network state)
      (fn [cur]
        (into
          {}
          (remove #(> too-old (:last-seen (second %))) cur))))))

(defn- send-host-info
  "Sends a message about host information... how to HTTP to Tron"
  [dest {:keys [::queue ::opts] :as state}]
  (.sendMessage
    ^MessageBroker queue
    dest
    {:content-type "application/json"}
    {:action    "tron-info"
     :msg-id    (fu/random-uuid)
     :tron-host (fu/compute-host-and-port opts)
     :at        (System/currentTimeMillis)
     })
  )

(defn- try-to-load-route-map
  "Try to load the route map"
  [opts bundles]
  (let [file (fu/new-file (common/calc-storage-directory opts) "route_map.data")]
    (try
      (if (.exists file)
        (let [data (slurp file)
              data (read-string data)]
          (if (map? data)
            (let [data (into {} (filter #(contains? bundles (-> % second :sha)) data))]
              data))))
      (catch Exception e
        (do
          (error e "Failed to load route map")
          [])))))


(defn- alter-listening
  "Tell the runner to either listen to the bundle or sto stop listening"
  [sha {:keys [from]} {:keys [::queue ::opts]} action]

  (let [message-queue queue]
    (.sendMessage
      ^MessageBroker message-queue
      from
      {:content-type "application/json"}
      {:action    action
       :tron-host (fu/compute-host-and-port opts)
       :msg-id    (fu/random-uuid)
       :at        (System/currentTimeMillis)
       :sha       sha})))


(defn- tell-runners-to-alter-listening
  "Tell all the runners to stop listening to a sha"
  [sha {:keys [::network] :as state} action]

  (clean-network state)
  (doseq [[k {:keys [type]}] @network]
    (cond
      (= type "runner")
      (fu/run-in-pool (fn [] (alter-listening sha k state action)))
      ))
  )

(defn- add-to-route-map
  "Adds a route to the route table"
  [host path bundle-sha properties route-map-atom state]
  (let [sha (fu/route-to-sha host path)
        data {:host host :path path :queue sha :sha bundle-sha :props properties}]
    (tell-runners-to-alter-listening bundle-sha state "enable")
    (swap!
      route-map-atom
      (fn [rm]
        (let [rm (remove #(= (:queue %) sha) rm)
              rm (conj rm data)
              rm (sort
                   (fn [{:keys [path]} y]
                     (let [py (:path y)]
                       (- (count py) (count path)))
                     )
                   rm)]
          (into [] rm))
        ))))


(defn- remove-from-route-map
  "Removes a func bundle from the route map"
  [_ _ bundle-sha route-map-atom state]

  (tell-runners-to-alter-listening bundle-sha state "disable")

  (swap!
    route-map-atom
    (fn [rm]
      (let [rm (remove #(= (:sha %) bundle-sha) rm)]
        (into [] rm))
      )))

(defn- remove-other-instances-of-this-frontend
  "Finds other front-end instances with the same `instance-id` and tell them to die"
  [{:keys [from instance-id]} {:keys [::network ::queue]}]
  (let [to-kill (filter (fn [[k v]]
                          (and (not= k from)
                               (= instance-id (:instance-id v))))
                        @network
                        )
        kill-keys (into #{} (map first to-kill))
        ]

    ;; no more messages to the instances we're removing
    (swap! network
           (fn [m]
             (into {} (remove #(kill-keys (first %)) m))))
    (doseq [[k {:keys [instance-id]}] to-kill]
      (info (str "Killing old id " k " with instance-id " instance-id))
      (.sendMessage
        ^MessageBroker queue
        k
        {:content-type "application/json"}
        {:action      "die"
         :msg-id      (fu/random-uuid)
         :instance-id instance-id
         :at          (System/currentTimeMillis)
         }))))

(defn- send-route-map
  "Sends the route map to a destination"
  ([where {:keys [::queue ::route-map]}]
   (.sendMessage
     ^MessageBroker queue where
     {:content-type "application/json"}
     {:action "route"
      :msg-id (fu/random-uuid)
      :routes (or @route-map [])
      :at     (System/currentTimeMillis)
      })))

(defn- routes-changed
  "The routes changed, let all the front end instances know"
  [{:keys [::network ::opts] :as state} _ _ new-routes]
  (fu/run-in-pool
    (fn []
      ;; write the file
      (let [file (fu/new-file (common/calc-storage-directory opts) "route_map.data")]
        (try
          (spit file (pr-str new-routes))
          (catch Exception e (error e "Failed to write route map"))
          ))

      (clean-network state)
      (doseq [[k {:keys [type]}] @network]
        (cond
          (= type "frontend")
          (send-route-map k state)

          )))))

#_(defn ^StableStore backing-store
    "Returns the backing store object"
    []
    @-backing-store)

(defn- no-file-with-same-sha
  "Make sure there are no files in the storage-directory that have the same sha"
  [sha-to-test {:keys [::opts]}]
  (let [sha (URLEncoder/encode sha-to-test)
        files (filter #(.contains ^String % sha) (.list ^File (common/calc-storage-directory opts)))]
    (empty? files)))

(defn- upload-func-bundle
  "Get a func bundle"
  [{:keys [body]} {:keys [::opts ::bundles] :as state}]
  (if body
    (let [file (File/createTempFile "func-a-" ".tron")]
      (cio/copy body file)
      (try
        ;; load the file and make sure it's a valid func bundle
        (let [thing (jarjar/build-router file {})]
          (.endLife thing))
        (let [{:keys [sha type swagger host basePath file-info]} (common/sha-and-swagger-for-file file)]
          (if (and type file-info)
            (let [dest-file (fu/new-file
                              (common/calc-storage-directory opts)
                              (str (System/currentTimeMillis)
                                   "-"
                                   (URLEncoder/encode sha) ".funcbundle"))]
              (when (no-file-with-same-sha sha state)
                (cio/copy file dest-file))
              (.delete file)
              (swap! bundles assoc sha {:file dest-file :swagger swagger})
              {:status 200
               :body   {:accepted true
                        :type     type
                        :host     host
                        :route    basePath
                        :swagger  swagger
                        :sha      sha}})
            {:status 400
             :body   {:accepted false
                      :error    "Could not determine the file type"}}))
        (catch Exception e {:status 400
                            :body   {:accepted false
                                     :error    (.toString e)}}))
      )
    {:status 400
     :body   {:accepted false
              :error    "Must post a Func bundle file"}}
    )
  )

(defn- enable-func
  "Enable a Func bundle"
  [{:keys [json-params]} {:keys [::bundles ::route-map] :as state}]
  (let [json-params (fu/keywordize-keys json-params)
        {:keys [sha props]} json-params]
    (if (not (and json-params sha))
      ;; failed
      {:status 400
       :body   {:success false :error "Request must be JSON and include the 'sha' of the Func bundle to enable"}}

      ;; find the Func bundle and enable it
      (let [{{:keys [host basePath]} :swagger :as bundle} (get @bundles sha)]
        (if (not bundle)
          ;; couldn't find the bundle
          {:status 404
           :body   {:success false :error (str "Could not find Func bundle with SHA: " sha)}}

          (do
            (add-to-route-map host basePath sha props route-map state)
            {:status 200
             :body   {:success true :msg (str "Deployed Func bundle host: " host " basePath " basePath " sha " sha)}})
          ))
      ))

  )

(defn- disable-func
  "Disable a Func bundle"
  [{:keys [json-params] {:keys [sha]} :json-params} {:keys [::bundles ::route-map] :as state}]
  (if (not (and json-params sha))
    ;; failed
    {:status 400
     :body   {:success false :error "Request must be JSON and include the 'sha' of the Func bundle to disable"}}

    ;; find the Func bundle and enable it
    (let [{{:keys [host basePath]} :swagger :as bundle} (get @bundles sha)]
      (if (not bundle)
        ;; couldn't find the bundle
        {:status 404
         :body   {:success false :error (str "Could not find Func bundle with SHA: " sha)}}

        (do
          (remove-from-route-map host basePath sha route-map state)
          {:status 200
           :body   {:success true :msg (str "Disabled Func bundle host: " host " basePath " basePath " sha " sha)}})
        ))
    ))

(defn- bundles-from-state
  "Pass in the state object and get a list of func bundles"
  [{:keys [::bundles]}]
  (map (fn [[k {{:keys [host basePath]} :swagger}]]
         {:sha  k
          :host host
          :path basePath})
       @bundles))

(defn- get-known-funcs
  "Return all the known func bundles"
  [_ state]
  {:status 200
   :body   {:func-bundles (bundles-from-state state)}})

(defn- get-stats
  "Return statistics on activity"
  [_ _]
  {:status 200
   :body   {:success false
            :action  "FIXME"}
   })

(defn- return-sha
  "Get the Func bundle with the sha"
  [req {:keys [::bundles]}]
  (let [sha (-> req :params :sha)]
    (if-let [{:keys [file]} (get @bundles sha)]
      {:status 200
       :body   (clojure.java.io/input-stream file)}

      {:status 404
       :body   (str "No func bundle with sha " sha " found")}
      )
    )
  )

(defn tron-routes
  "Routes for Tron"
  [state]
  (-> (routes
        (POST "/api/v1/enable" req (enable-func req state))
        (POST "/api/v1/disable" req (disable-func req state))
        (GET "/api/v1/stats" req (get-stats req state))
        (GET "/api/v1/known_funcs" req (get-known-funcs req state))
        (GET "/api/v1/bundle/:sha" req (return-sha req state))
        (POST "/api/v1/add_func"
              req (upload-func-bundle req state)))
      wrap-json-response
      (rm-json/wrap-json-params)))

(defmulti dispatch-tron-message
          "Dispatch the incoming message"
          (fn [msg & _] (-> msg :action))
          )

(defmethod dispatch-tron-message "heartbeat"
  [{:keys [from]} _ {:keys [::network] :as state}]
  (trace (str "Heartbeat from " from))
  (clean-network state)
  (swap! network assoc-in [from :last-seen] (System/currentTimeMillis))
  (send-host-info from state)
  )

(defn- send-func-bundles
  "Send a list of the Func bundles to the Runner as well
  as the host and port for this instance"
  [destination {:keys [::queue ::opts] :as state}]
  (.sendMessage
    ^MessageBroker queue
    destination
    {:content-type "application/json"}
    {:action    "all-bundles"
     :tron-host (fu/compute-host-and-port opts)
     :msg-id    (fu/random-uuid)
     :at        (System/currentTimeMillis)
     :bundles   (bundles-from-state state)
     }))

(defmethod dispatch-tron-message "awake"
  [{:keys [from type] :as msg} _ {:keys [::network] :as state}]
  (info (str "awake from " msg))
  (clean-network state)
  (swap! network assoc from
         (merge
           msg
           {:last-seen (System/currentTimeMillis)}))

  (send-host-info from state)

  (cond
    (= "frontend" type)
    (do
      (send-route-map from state)
      (remove-other-instances-of-this-frontend msg state)
      )

    (= "runner" type)
    (do
      (send-func-bundles from state))))

(defmethod dispatch-tron-message "died"
  [{:keys [from]} _ {:keys [::network]}]
  (info (str "Got 'died' message from " from))
  (swap! network dissoc from)
  )

(defn- handle-tron-messages
  "Handle messages sent to the tron queue"
  [state ^MessageBroker$ReceivedMessage msg]
  (fu/run-in-pool
    (fn []
      (let [body (.body msg)
            body (fu/keywordize-keys body)]
        (try
          (trace (str "Got message. Action " (:action body) " from " (:from body)))
          (dispatch-tron-message body msg state)
          (catch Exception e (error e (str "Failed to dispatch message: " body))))))))

(defn- build-handler-func
  [state]
  (let [ver (atom file-version)
        the-func (atom (tron-routes state))]
    (fn [req]
      (when (not= @ver file-version)
        (reset! ver file-version)
        (reset! the-func (tron-routes state)))
      (@the-func req)
      )))

(defn ^Lifecycle build-tron
  "Builds a Tron instance as a Lifecycle"
  [^MessageBroker queue opts]

  (let [bundles (atom {})
        route-map (atom [])
        network (atom {})
        shutdown-http-server (atom nil)
        this (atom nil)
        state {::queue                queue
               ::bundles              bundles
               ::network              network
               ::opts                 opts
               ::this                 this
               ::shutdown-http-server shutdown-http-server
               ::route-map            route-map}
        ]

    (.findAndRegisterModules (ObjectMapper.))

    (add-watch route-map state routes-changed)

    (let [ret (reify Lifecycle
                (startLife [_]
                  (info (str "Starting Tron lifecycle"))

                  (reset! bundles (common/load-func-bundles (common/calc-storage-directory opts))) ;; load the bundles

                  (info (str "Loaded bundles... " (count @bundles)))

                  (reset! route-map (try-to-load-route-map opts @bundles))


                  (let [{:keys [host port]} (fu/compute-host-and-port opts)]
                    (info (str "Tron running at host " host " and port " port))

                    (info (str "Upload a Func Bundle: wget -q -O - --post-file=THE_UBERJAR http://" host ":" port "/api/v1/add_func\n"))

                    (info (str "List known Func Bundles: curl -v http://"
                               host
                               ":"
                               port
                               "/api/v1/known_funcs"))

                    (info (str "Enable a Func Bundle: curl -v -H \"Content-Type: application/json\" -d '{\"sha\":\"THE-SHA-HERE\", \"props\": {\"key\": \"value\"}}' -X POST http://"
                               host
                               ":"
                               port
                               "/api/v1/enable"))


                    )

                  (reset! shutdown-http-server (fu/start-http-server opts (build-handler-func state)))
                  (shared-b/listen-to-queue
                    queue
                    (common/tron-queue)
                    (partial handle-tron-messages state))
                  )

                (endLife [_]
                  (info (str "Ending Tron Lifecycle"))
                  (shared-b/close-all-listeners queue)
                  (.close queue)
                  (@shutdown-http-server))

                (allInfo [_] {::message-queue queue
                              ::func-bundles  @bundles
                              ::routes        @route-map
                              ::this          @this
                              ::network       @network})
                )]
      (reset! this ret)
      ret
      )))

(defn ^Lifecycle build-tron-from-opts
  "Builds the runner from options... and if none are passed in, use global options"
  ([] (build-tron-from-opts @opts/command-line-options))
  ([opts]
   (require                                                 ;; load a bunch of the namespaces to register wiring
     '[funcatron.tron.brokers.rabbitmq]
     '[funcatron.tron.brokers.inmemory]
     '[funcatron.tron.store.zookeeper]
     '[funcatron.tron.substrate.mesos-substrate])
   (let [queue (shared-b/wire-up-queue opts)]
     (build-tron queue opts))))