(ns ceci.env
  (:refer-clojure :exclude [create-ns ns ns-name resolve])
  (:require [clojure.string :as string]
            [ceci.util :refer [update]]))

;; namespace management

(def namespaces (atom {}))
(def ns-name (atom 'user))

(defn require-ns
  "Within `ns-spec`, requires the namespace `required-ns` under the alias
  `ns-alias`."
  [ns-spec required-ns ns-alias]
  (if ns-alias
      (update ns-spec :require merge {ns-alias required-ns})
      ns-spec))

(defn refer-symbols
  "Within `ns-spec`, refers all symbols in `referred-symbols` to the symbols
  with the same names defined in `required-ns`."
  [ns-spec required-ns referred-symbols]
  (if referred-symbols
      (update ns-spec :refer merge
              (->> referred-symbols
                   (map (juxt identity (constantly required-ns)))
                   (into {})))
      ns-spec))

(def core-defs
  '[+ - * / = > >= < <= and apply assoc assoc-in boolean comp concat conj cons
    constantly dec dissoc filter fnil get get-in hash hash-map identity
    inc interpose into juxt key keys keyword keyword? list list? map map? merge
    nil? not not= number? or partial print println pr prn pr-str reduce remove
    reset! seq seq? set set? str swap! update-in val vals vec vector vector?])

(defn create-ns
  "Creates a new namespace called `name`, populated with public defs from
  `cljs.core`, and registers the newly created namespace in the global
  `namespaces` atom."
  [name]
  (let [ns-spec (refer-symbols {} 'cljs.core core-defs)]
    (swap! namespaces assoc name ns-spec)))

(create-ns 'user)

;; the `ns` macro

(defn add-clause [ns-spec [type & body]]
  (if (= type :require)
      (reduce (fn [ns-spec [required-ns & opts]]
                (let [{referred-symbols :refer ns-alias :as}
                      (apply hash-map opts)]
                  (-> ns-spec
                    (require-ns required-ns ns-alias)
                    (refer-symbols required-ns referred-symbols))))
              ns-spec body)
      ns-spec))

(defn ns [new-ns-name & clauses]
  (let [ns-spec
        (loop [ns-spec {} clauses clauses]
          (if-let [clause (first clauses)]
                  (recur (add-clause ns-spec clause) (rest clauses))
                  ns-spec))]
    `(do (swap! clueless.env/namespaces assoc ~new-ns-name ~ns-spec)
         (reset! clueless.env/ns-name ~new-ns-name))))

;; symbol expansion

(defn symbol-parts [sym]
  (let [sym-str (str sym)
        parts (string/split sym-str #"/" 2)]
    (cond (every? empty? parts) [nil "/"]
          (= (count parts) 1) [nil (first parts)]
          :else parts)))

(defn resolve-ns-alias [ns-alias ns-spec]
  (when ns-alias (get-in ns-spec [:require (symbol ns-alias)])))

(defn resolve-defining-ns [sym-name ns-spec]
  (get-in ns-spec [:refer (symbol sym-name)]))

(defn namespace-named [ns-name]
  (get @namespaces (symbol ns-name)))

(defn resolve
  "Given a potentially unqualified or only partly qualified symbol `sym`,
  returns the fully qualified version of that symbol in the context of
  namespace specification `ns-spec` (defaulting to the current working
  namespace specification if none is specified)."
  ([sym] (resolve sym (namespace-named @ns-name)))
  ([sym ns-spec]
    (let [[ns-part name-part] (symbol-parts sym)
          ns-part (or (resolve-ns-alias ns-part ns-spec)
                      (when (or (namespace-named ns-part) (= ns-part "js"))
                            ns-part)
                      (resolve-defining-ns name-part ns-spec)
                      (str @ns-name))]
      (symbol ns-part name-part))))