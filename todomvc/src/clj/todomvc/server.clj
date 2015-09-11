(ns todomvc.server
  (:require [clojure.java.io :as io]
            [todomvc.util :as util]
            [ring.util.response :refer [response file-response resource-response]]
            [ring.adapter.jetty :refer [run-jetty]]
            [todomvc.middleware
             :refer [wrap-transit-body wrap-transit-response
                     wrap-transit-params]]
            [ring.middleware.resource :refer [wrap-resource]]
            [bidi.bidi :refer [make-handler] :as bidi]
            [com.stuartsierra.component :as component]
            [datomic.api :as d]
            [todomvc.datomic]))

;; =============================================================================
;; Routes

(def routes
  ["" {"/" :index
       "/api"
        {:get  {[""] :api}
         :post {[""] :api}}}])

;; =============================================================================
;; Handlers

(defn index [req]
  (assoc (resource-response (str "html/index.html") {:root "public"})
    :headers {"Content-Type" "text/html"}))

(defn generate-response [data & [status]]
  {:status  (or status 200)
   :headers {"Content-Type" "application/transit+json"}
   :body    data})

;; CONTACT HANDLERS

(defn todomvc [conn selector]
  (todomvc.datomic/todomvc (d/db conn) selector))

(defmulti -route (fn [_ k _] k))

(defmethod -route :app/todomvc
  [conn _ selector]
  (todomvc conn selector))

(defn route
  ([conn k] (route conn k '[*]))
  ([conn k selector]
    (-route conn k selector)))

(defn router [conn query]
  (letfn [(step [ret k]
            (cond
              (map? k)
              (let [[k v] (first k)]
                (assoc ret k (route conn k v)))

              (keyword? k)
              (assoc ret k (router conn k))

              :else
              (throw
                (ex-info (str "Invalid query key " k)
                  {:type :error/invalid-query-value}))))]
    (reduce step {} query)))

(defn api [req]
  (generate-response
    (router (:datomic-connection req) (:transit-params req))))

;;;; PRIMARY HANDLER

(defn handler [req]
  (let [match (bidi/match-route routes (:uri req)
                :request-method (:request-method req))]
    ;(println match)
    (case (:handler match)
      :index (index req)
      :api   (api req)
      req)))

(defn wrap-connection [handler conn]
  (fn [req] (handler (assoc req :datomic-connection conn))))

(defn todomvc-handler [conn]
  (wrap-resource
    (wrap-transit-response
      (wrap-transit-params (wrap-connection handler conn)))
    "public"))

(defn todomvc-handler-dev [conn]
  (fn [req]
    ((todomvc-handler conn) req)))

;; =============================================================================
;; WebServer

(defrecord WebServer [port handler container datomic-connection]
  component/Lifecycle
  (start [component]
    (let [conn (:connection datomic-connection)]
      (if container
       (let [req-handler (handler conn)
             container (run-jetty req-handler
                         {:port port :join? false})]
         (assoc component :container container))
       ;; if no container
       (assoc component :handler (handler conn)))))
  (stop [component]
    (.stop container)))

(defn dev-server [web-port]
  (WebServer. web-port todomvc-handler-dev true nil))

(defn prod-server []
  (WebServer. nil todomvc-handler false nil))

;; =============================================================================
;; Route Testing

(comment
  (require '[todomvc.core :as cc])

  (cc/dev-start)

  ;; get todos
  (handler
    {:uri "/api"
     :request-method :post
     :transit-params [{:todos [:todo/completed :todo/title]}]
     :datomic-connection (:connection (:db @cc/servlet-system))})

  (.basisT (d/db (:connection (:db @cc/servlet-system))))

  ;; create todo
  (handler {:uri "/api"
            :request-method :post
            :transit-params '[(todo/create {:todo/title "New Todo"})]
            :datomic-connection (:connection (:db @cc/servlet-system))})

  )

