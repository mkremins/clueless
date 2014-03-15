(ns ceci.compiler
  (:require [clojure.string :as string]
            [ceci.analyzer :as analyzer]
            [ceci.emitter :as emitter]
            [ceci.expander :as expander]
            [ceci.reader :as reader])
  (:refer-clojure :exclude [compile slurp spit]))

(enable-console-print!)

(def fs (js/require "fs"))

(defn slurp [fpath]
  (.readFileSync fs fpath))

(defn spit [fpath contents]
  (.writeFileSync fs fpath contents))

(defn write-js [cljs-source]
  (->> cljs-source
       (reader/read-code)
       (map expander/expand-all)
       (map analyzer/form->ast)
       (map analyzer/analyze)
       (map emitter/emit)
       (string/join "\n")))

(defn compile [in-file out-file]
  (spit out-file (write-js (slurp in-file))))

(set! *main-cli-fn* compile)