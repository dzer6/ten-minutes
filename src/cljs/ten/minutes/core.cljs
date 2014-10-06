(ns ten.minutes.core
  (:require-macros
    [cljs.core.async.macros :as asyncm :refer (go go-loop)]
    [jayq.macros :as jqm])
  (:require
    [clojure.string  :as str]
    [reagent.core :as reagent :refer [atom]]
    [jayq.core :as jq]
    [cljs-time.coerce :as tc]
    [cljs-time.format :as tf]
    [cljs.core.async :as async  :refer (<! >! put! chan)]
    [taoensso.encore :as encore :refer (logf)]
    [taoensso.sente  :as sente  :refer (cb-success?)]))

(enable-console-print!)

(logf "ClojureScript appears to have loaded correctly.")

(defonce sente-socket
  (sente/make-channel-socket! "/chsk" {:type :auto }))

(let [{:keys [chsk ch-recv send-fn state]} sente-socket]
  (defonce chsk       chsk)
  (defonce ch-chsk    ch-recv) ; ChannelSocket's receive channel
  (defonce chsk-send! send-fn) ; ChannelSocket's send API fn
  (defonce chsk-state state)   ; Watchable, read-only atom
)

(defonce app-state (atom {:email "loading"}))

;;;; Routing handlers

(defmulti chsk-recv-event-msg-handler #(first %))

(defmulti event-msg-handler :id) ; Dispatch on event-id
;; Wrap for logging, catching, etc.:
(defn event-msg-handler* [{:as ev-msg :keys [id ?data event]}]
  (logf "Event: %s" event)
  (event-msg-handler ev-msg))

(do
  (defmethod chsk-recv-event-msg-handler :message/new
    [[event ?data]]
    (logf ">>>>> :message/new %s" ?data)
    (let [messages (:messages @app-state)
          messages (apply vector (cons ?data messages))]
      (reset! app-state (assoc @app-state :messages messages))))

  (defmethod chsk-recv-event-msg-handler :account/delete
    [[event ?data]]
    (logf ">>>>> :account/delete %s" ?data)
    (reset! app-state {:deleted true}))

  )

(do ; Client-side methods
  (defmethod event-msg-handler :default ; Fallback
    [{:as ev-msg :keys [event]}]
    (logf "Unhandled event: %s" event))

  (defmethod event-msg-handler :chsk/state
    [{:as ev-msg :keys [?data]}]
    (if (= ?data {:first-open? true})
      (logf "Channel socket successfully established!")
      (logf "Channel socket state change: %s" ?data))
    (chsk-send! [:client/ready {:had-a-callback? true}] 5000
      (fn [cb-reply]
        (logf ":client/ready callback reply: %s" cb-reply)
        (reset! app-state cb-reply))))

  (defmethod event-msg-handler :chsk/recv
    [{:as ev-msg :keys [?data]}]
    (logf "Push event from server: %s" ?data)
    (chsk-recv-event-msg-handler ?data))

  ;; Add your (defmethod handle-event-msg! <event-id> [ev-msg] <body>)s here...
  )

;;

(defn format-time [l]
  (tf/unparse
    (tf/formatter "MM/dd/yyyy HH:mm")
    (tc/from-long l)))

(defn find-message-index [messages time]
  (loop [items messages
         index 0]
    (when-first [item items]
      (if (= time (:time item))
        index
        (recur (rest items) (inc index))))))

(defn get-selected-message []
  (let [messages (:messages @app-state)
        time (:selected @app-state)
        index (find-message-index messages time)]
    (get messages index)))

(defn message-list []
  (let [selected (:selected @app-state)
        messages (:messages @app-state)]
    [:table.table.table-hover
     [:tbody
      (for [m messages]
        [:tr {:on-click #(reset! app-state (assoc @app-state :selected (:time m)))
              :class (when (= (:time m) selected) "info")}
         [:td
          [:div.width-120-px (format-time (:time m))]]
         [:td.width-100-prcnt
          [:b (:subject m)]]
         [:td
          [:span.label.label-primary (:type m)]]])]]))

(defn income-message []
  (let [message (get-selected-message)
        {:keys [from to time subject body]} message]
    (if message
      [:div.panel.panel-default
       [:div.panel-body
        [:div.row
         [:div.col-md-12
          [:h3 subject]
          [:hr]]
         [:div.col-md-12
          [:b "from: "] from]
         [:div.col-md-12
          [:b "to: "] to]
         [:div.col-md-12
          [:br]
          [:p body]]]]]
      [:div.panel.panel-default
       [:div.panel-body
        [:p.text-center "Please, select message"]]])))

(defn center-block []
  (if (empty? (:messages @app-state))
    [:div.panel.panel-default
     [:div.panel-body
      [:p.text-center (if (:deleted @app-state) "Account expired, please reload the page" "Empty inbox")]]]
    [:div.row
     [:div.col-md-6
      [message-list]]
     [:div.col-md-6
      [income-message]]]))

(defn send-message-handler []
  (let [to (jq/val (jq/$ "#to"))
        subject (jq/val (jq/$ "#subject"))
        body (jq/val (jq/$ "#body"))]
    (chsk-send! [:message/send {:to to :subject subject :body body}])
    (jq/val (jq/$ "#to") "")
    (jq/val (jq/$ "#subject") "")
    (jq/val (jq/$ "#body") "")))

(defn write-message []
  [:div.panel.panel-default
   [:div.panel-body.write-message
    [:div.row
     [:div.col-md-12
      [:input.form-control {:type "text" :id "to" :placeholder "Recipient"}]]
     [:div.col-md-12
      [:input.form-control {:type "text" :id "subject" :placeholder "Subject"}]]
     [:div.col-md-12
      [:textarea.form-control {:rows 5 :id "body" }]]
     [:div.col-md-12
      [:input.btn.btn-primary {:value "Send" :on-click send-message-handler}]]]]])

(defn app []
  [:div
   [:div.container
    [:div.row
     [:div.col-md-12
      [:h1 (:email @app-state)]]]
    [center-block]
    [:div.row
     [:div.col-md-12
      [write-message]]]]])

;;;; Init

(sente/start-chsk-router! ch-chsk event-msg-handler*)

(defn mount-it []
  (prn "mount-it")
  (reagent/render-component
    (fn [] [app])
    (.-body js/document)
    (fn [])))

(defn unmount-it []
  (prn "unmount-it")
  (reagent/unmount-component-at-node (.-body js/document)))

(jqm/ready
  (mount-it))