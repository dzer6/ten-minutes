(ns ten.minutes.figwheel
  (:require [ten.minutes.core :as core]
            [figwheel.client :as fw]))

(prn "init figwheel client")

(fw/watch-and-reload
  :websocket-url "ws://localhost:3449/figwheel-ws"
  :jsload-callback (fn []
                     (do
                       (core/unmount-it)
                       (core/mount-it))))