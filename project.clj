(defproject ten-minutes "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Apache License, version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"}

  :dependencies [[org.clojure/clojure         "1.6.0"]
                 [compojure                   "1.1.6"]
                 [eigenhombre/namejen         "0.1.0"]
                 [org.subethamail/subethasmtp "3.1.7"]
                 [dnsjava/dnsjava             "2.1.6"]
                 [com.draines/postal          "1.11.1"]
                 [ring/ring-defaults          "0.1.2"]
                 [ring                        "1.3.1"]
                 [hiccup                      "1.0.5"]
                 [com.cognitect/transit-clj   "0.8.247"]
                 [com.cognitect/transit-cljs  "0.8.188"]
                 [com.taoensso/sente          "1.1.0"]
                 [org.clojure/clojurescript   "0.0-2322"]
                 [org.clojure/core.async      "0.1.338.0-5c5012-alpha"]
                 [org.clojure/tools.reader    "0.8.8"]
                 [com.taoensso/encore         "1.8.1"]
                 [org.clojure/tools.logging   "0.2.6"]
                 [org.slf4j/slf4j-api         "1.7.7"]
                 [org.slf4j/log4j-over-slf4j  "1.7.7"]
                 [ch.qos.logback/logback-classic "1.1.2"]
                 [http-kit                    "2.1.19"]
                 [jayq                        "2.5.1"]
                 [com.andrewmcveigh/cljs-time "0.1.6"]
                 [reagent                     "0.4.3-SNAPSHOT"]]

  :main ten.minutes.main

  :uberjar-name "ten-minutes-standalone.jar"

  :source-paths ["src/clj"]
  :test-paths ["test/clj"]

  :resources-path "resources"

  :plugins [[lein-cljsbuild "1.0.3"]
            [lein-bower "0.5.1"]]

  :bower-dependencies [[react "0.11.2"]
                       [bootstrap "3.2.0"]
                       [jquery-ui "1.11.1"]
                       [jquery "2.1.1"]
                       [jquery-file-upload "9.8.0"]]

  :bower {:directory "resources/public/lib"}

  :cljsbuild {:builds
              {:main {:source-paths ["src/cljs" ]
                      :compiler {:output-to "resources/public/app/main.js"
                                 :output-dir "resources/public/app/main"
                                 :source-map "resources/public/app/main.js.map"}}
               :test {:source-paths ["src/cljs" "test/cljs" ]
                      :compiler {:pretty-print true}}}}

)
