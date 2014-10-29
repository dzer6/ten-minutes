(ns ten.minutes.main
  (:gen-class)
  (:require
    [clojure.string     :as str]
    [compojure.core     :as comp :refer (defroutes GET POST)]
    [compojure.route    :as route]
    [ring.middleware.defaults]
    [namejen.core       :as namejen]
    [postal.core        :as postal]
    [hiccup.core        :as hc]
    [hiccup.page        :as hp]
    [hiccup.element     :as he]
    [org.httpkit.server :as http-kit-server]
    [clojure.core.async :as async :refer (<! <!! >! >!! put! chan go go-loop)]
    [clojure.tools.logging :as log]
    [taoensso.sente     :as sente]
    [ring.middleware.anti-forgery :as ring-anti-forgery])
  (:import (org.subethamail.smtp.helper SimpleMessageListener)
           (org.xbill.DNS Record Type MXRecord Lookup )))

(def accounts (ref {}))

(defn my-domain []
  "10-minutes-mail.com")

(defn get-mx-record [addr]
  (let [host (last (str/split addr #"@"))]
    (if (= host (my-domain))
      "localhost"
      (let [records (-> (Lookup. host Type/MX) .run)
            record (reduce #(if (> (.getPriority %1) (.getPriority %2)) %1 %2) records)]
        (-> record .getTarget .toString)))))

(def make-name (namejen/name-maker 3 (namejen/get-default-name-data)))

(defn my-smtp-port []
  2525)

(defn make-addr []
  (-> (make-name)
    str/lower-case
    (str/replace " " "-")
    (str/replace "." "")
    (str/replace "," "")
    (str "@" (my-domain))))

(let [{:keys [ch-recv send-fn ajax-post-fn ajax-get-or-ws-handshake-fn connected-uids]} (sente/make-channel-socket!)]
  (def ring-ajax-post                ajax-post-fn)
  (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (def ch-chsk                       ch-recv) ; ChannelSocket's receive channel
  (def chsk-send!                    send-fn) ; ChannelSocket's send API fn
  (def connected-uids                connected-uids) ; Watchable, read-only atom
  )

(defn now []
  (System/currentTimeMillis))

(defn account-expired? [addr]
  (let [used ((@accounts addr) :used)
        now (now)]
    (if (> (- now used)  (* 10 60 1000))
      true
      false)))

(defn delete-account [addr]
  (log/info "delete-account, addr =" addr)
  (dosync
    (alter accounts dissoc addr))
  (chsk-send! addr [:account/delete nil]))

(defn watch-accounts []
  (future
    (while true
      (doseq [addr (keys @accounts)]
        (when (account-expired? addr)
          (delete-account addr)))
      (Thread/sleep 100))))

(defn account-active? [addr]
  (log/info "account-active?, addr =" addr ", result =" (contains? @accounts addr))
  (contains? @accounts addr))

(defn new-account []
  (loop [addr (make-addr)]
    (if (account-active? addr)
      (recur (make-addr))
      (do
        (log/info "new-account, addr =" addr)
        (dosync
          (alter accounts assoc addr {:used (now) :messages []}))
        addr))))

(defn add-message [uid m]
  (let [messages ((@accounts uid) :messages)
        messages (apply vector (cons m messages))]
    (log/info "add-message, message =" m)
    (dosync
      (alter accounts assoc uid {:used (now) :messages messages}))
    (chsk-send! uid [:message/new m])))

(defn process-message [from to message]
  (let [time (now)
        subject (.getSubject message)
        body (.getContent message)
        m {:from from :to to :time time :subject subject :body body :type "received"}]
    (log/info "process-message, message =" m)
    (add-message to m)))


(defn get-messages [addr]
  (get-in @accounts [addr :messages]))

(defn message-listener []
  (proxy [SimpleMessageListener] []
    (accept [from to]
      (account-active? to))

    (deliver [from to data]
      (log/info "message-listener deliver, from =" from ", to =" to ", data =" data)
      (process-message from to
        (-> (java.util.Properties.)
          javax.mail.Session/getDefaultInstance
          (javax.mail.internet.MimeMessage. data))))))

(def smtp-server
  (org.subethamail.smtp.server.SMTPServer.
    (org.subethamail.smtp.helper.SimpleMessageListenerAdapter.
      (message-listener))))

(defn app-html []
  (hp/html5 {:ng-app "ten-minutes" :lang "en"}
    [:head
     [:title "Ten Minutes"]
     (hp/include-css "/lib/bootstrap/dist/css/bootstrap.min.css")
     (hp/include-css "/lib/bootstrap/dist/css/bootstrap-theme.min.css")
     (hp/include-css "/css/app.css")
     [:body
      (hp/include-js "/lib/react/react.js")
      (hp/include-js "/lib/bootstrap/dist/js/bootstrap.js")
      (hp/include-js "/app/main/goog/base.js")
      (hp/include-js "/app/main.js")
      (he/javascript-tag "goog.require(\"ten.minutes.core\"); goog.require(\"ten.minutes.figwheel\");")]]))

(defn landing-pg-handler [req]
  (let [{:keys [session]} req
        uid (:uid session)
        html (app-html)]
    (if (account-active? uid) html
      (let [name (new-account)]
        {:status 200
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :session (assoc session :uid name)
         :body html}))))

(defroutes my-routes
  (GET  "/"      req (landing-pg-handler req))
  ;;
  (GET  "/chsk"  req (ring-ajax-get-or-ws-handshake req))
  (POST "/chsk"  req (ring-ajax-post                req))
  ;;
  (route/resources "/") ; Static files, notably public/main.js (our cljs target)
  (route/not-found "<h1>Page not found</h1>"))


(def my-ring-handler
  (let [ring-defaults-config
        (assoc-in ring.middleware.defaults/site-defaults [:security :anti-forgery]
          {:read-token (fn [req] (-> req :params :csrf-token))})]
    (ring.middleware.defaults/wrap-defaults my-routes ring-defaults-config)))

(defmulti event-msg-handler :id) ; Dispatch on event-id
;; Wrap for logging, catching, etc.:
(defn     event-msg-handler* [{:as ev-msg :keys [id ?data event]}]
  ;(log/info "Event: %s" event)
  (event-msg-handler ev-msg))


(do ; Server-side methods
  (defmethod event-msg-handler :default ; Fallback
    [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
    (let [session (:session ring-req)
          uid     (:uid     session)]
      ;(log/info "Unhandled event: %s" event)
      (when-not (:dummy-reply-fn (meta ?reply-fn))
        (?reply-fn {:umatched-event-as-echoed-from-from-server event}))))

  ;; Add your (defmethod event-msg-handler <event-id> [ev-msg] <body>)s here...

  (defmethod event-msg-handler :client/ready
    [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
    (let [session (:session ring-req)
          uid     (:uid     session)]
      (log/info ":client/ready, uid =" uid ", ev-msg =" ev-msg ", accounts =" @accounts)
      (when-not (:dummy-reply-fn (meta ?reply-fn))
        (?reply-fn {:email uid :messages (get-messages uid)}))))

  (defmethod event-msg-handler :message/send
    [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
    (let [session (:session ring-req)
          uid     (:uid     session)
          {:keys [to subject body]} ?data
          m {:from uid :to to :subject subject :body body}
          host (get-mx-record to)
          port (if (= host "localhost") (my-smtp-port) 25)]
      (log/info ":message/send, message =" m)
      (postal/send-message {:host host :port port} m)
      (add-message uid (assoc m :type "sent" :time (now)))))

  )

;;;; Init

(defonce http-server_ (atom nil))

(defn stop-http-server! []
  (when-let [stop-f @http-server_]
    (stop-f :timeout 100)))

(defn start-http-server! []
  (stop-http-server!)
  (let [s   (http-kit-server/run-server (var my-ring-handler) {:port 8080})
        uri (format "http://localhost:%s/" (:local-port (meta s)))]
    (reset! http-server_ s)
    (log/info "Http-kit server is running at `%s`" uri)))

(defonce router_ (atom nil))

(defn stop-router! [] (when-let [stop-f @router_] (stop-f)))
(defn start-router! []
  (stop-router!)
  (reset! router_ (sente/start-chsk-router! ch-chsk event-msg-handler*)))

(defn start! []
  (watch-accounts)
  (.setPort smtp-server (my-smtp-port))
  (.start smtp-server)
  (start-router!)
  (start-http-server!))

(defn -main [& args]
  (start!))

