(ns ceci.analyzer
  (:require [ceci.emitter :as emitter]
            [ceci.util :refer [raise update]]))

;; AST creation

(defn node-type [form]
  (cond (keyword? form) :keyword
        (list? form) :list
        (map? form) :map
        (nil? form) :nil
        (number? form) :number
        (set? form) :set
        (string? form) :string
        (symbol? form) :symbol
        (vector? form) :vector
        :else (raise "unrecognized form type" form)))

(defn form->ast [form]
  (let [type (node-type form)
        ast {:form form :meta (meta form) :type type}]
    (if (coll? form)
        (assoc ast :op :coll :children (map form->ast form))
        (assoc ast :op :const))))

;; AST analysis

(declare analyze)

(def true-ast-node
  {:op :const :type :bool :form true})

(def false-ast-node
  {:op :const :type :bool :form false})

(def nil-ast-node
  {:op :const :type :nil :form nil})

(defn expr-env [env]
  (assoc env :context :expr))

(defn analyze-block [env exprs]
  (let [body-env (assoc env :context :statement)
        return-env (assoc env :context (if (= (:context env) :statement)
                                           :statement :return))]
    (conj (vec (map (partial analyze body-env) (butlast exprs)))
          (analyze return-env (last exprs)))))

;; simple special forms

(defn analyze-aget [env {[_ target & fields] :children :as ast}]
  (assoc ast :op :aget
    :target (analyze (expr-env env) target)
    :fields (map (partial analyze (expr-env env)) fields)))

(defn analyze-aset [env {[_ target & fields+value] :children :as ast}]
  (let [fields (drop-last fields+value)
        value (last fields+value)]
    (assoc ast :op :aset
      :target (analyze (expr-env env) target)
      :fields (map (partial analyze (expr-env env)) fields)
      :value (analyze (expr-env env) value))))

(defn analyze-def [env {[_ name & [init?]] :children :as ast}]
  (assoc ast :op :def
    :name (analyze (expr-env env) name)
    :init (analyze (expr-env env) (or init? nil-ast-node))))

(defn analyze-do [env {[_ & body] :children :as ast}]
  (assoc ast :op :do
    :body (analyze-block env body)))

(defn analyze-if [env {[_ test then else] :children :as ast}]
  (assoc ast :op :if
    :test (analyze (expr-env env) test)
    :then (analyze env then)
    :else (analyze env else)))

(defn analyze-new [env {[_ ctor & args] :children :as ast}]
  (assoc ast :op :new
    :ctor (analyze (expr-env env) ctor)
    :args (map (partial analyze (expr-env env)) args)))

(defn analyze-quote [env {[_ ast] :children}]
  (analyze (assoc env :quoted? true) ast))

(defn analyze-throw [env {[_ thrown] :children :as ast}]
  (assoc ast :op :throw
    :thrown (analyze (expr-env env) thrown)))

;; fn forms

(defn analyze-params [env {:keys [children]}]
  (let [raw-params (vec (map :form children))
        params (if (= (second (reverse raw-params)) '&)
                   (conj (vec (drop-last 2 raw-params))
                         (with-meta (last raw-params) {:rest-param? true}))
                   raw-params)]
    [(update env :locals concat params) params]))

(defn analyze-clauses [env clauses]
  (loop [analyzed {} env env clauses clauses]
    (if-let [[params & body] (first clauses)]
      (let [[env params] (analyze-params env params)
            clause {:params params :body (analyze-block env body)}]
        (recur (assoc analyzed (count params) clause) env (rest clauses)))
      analyzed)))

(defn extract-clauses
  "Given `ast`, an AST node representing a `fn` special form, extracts and
  returns a collection of clauses. Each clause is a sequence of AST nodes whose
  first item represents the params taken by that clause and whose remaining
  items comprise the clause body."
  [{[_ & args] :children :as ast}]
  (condp = (:type (first args))
    :symbol (let [args (rest args)]
              (condp = (:type (first args))
                :vector [(cons (first args) (rest args))]
                :list (map :children args)
                (raise "invalid function definition" (:form ast))))
    :vector [(cons (first args) (rest args))]
    :list (map :children args)
    (raise "invalid function definition" (:form ast))))

(defn analyze-fn [env ast]
  (let [clauses (extract-clauses ast)]
    (assoc ast :op :fn
      :clauses (analyze-clauses env clauses))))

;; let forms

(defn compile-bindings [bindings]
  (if (= (:type bindings) :vector)
    (let [pairs (partition 2 (:children bindings))]
      (if (= (count (last pairs)) 2)
        (vec (map (juxt (comp :form first) second) pairs))
        (raise "number of forms in bindings vector must be even" bindings)))
    (raise "bindings form must be vector" bindings)))

(defn analyze-bindings [env bindings]
  (loop [env env analyzed [] idx 0]
    (if-let [[left-hand right-hand] (get bindings idx)]
      (recur (update env :locals conj left-hand)
             (conj analyzed [left-hand (analyze (expr-env env) right-hand)])
             (inc idx))
      [env analyzed])))

(defn analyze-let [env {[_ bindings & body] :children :as ast}]
  (let [[body-env bindings] (analyze-bindings env (compile-bindings bindings))]
    (assoc ast :op :let
      :bindings bindings
      :body (analyze-block body-env body))))

;; loop and recur forms

(defn analyze-loop [env {[_ bindings & body] :children :as ast}]
  (let [[body-env bindings] (analyze-bindings env (compile-bindings bindings))
        ast (assoc ast :op :loop :bindings bindings)
        body-env (assoc body-env :recur-point ast)]
    (assoc ast :body (analyze-block body-env body))))

(defn analyze-recur [env {[_ & args] :children :as ast}]
  (let [recur-point (:recur-point env)]
    (if recur-point
        (assoc ast :op :recur
          :recur-point recur-point
          :args (vec (map (partial analyze (expr-env env)) args)))
        (raise "can't recur here – no enclosing loop" (:form ast)))))

;; generic interface

(defn analyze-coll [env ast]
  (update ast :children #(map (partial analyze (expr-env env)) %)))

(def specials
  {'aget analyze-aget
   'aset analyze-aset
   'def analyze-def
   'do analyze-do
   'fn* analyze-fn
   'if analyze-if
   'let* analyze-let
   'loop* analyze-loop
   'new analyze-new
   'quote analyze-quote
   'recur analyze-recur
   'throw analyze-throw})

(defn analyze-list [env {:keys [form children] :as ast}]
  (if (or (:quoted? env) (empty? children))
      (analyze-coll env ast)
      (if-let [analyze-special (specials (first form))]
        (analyze-special env ast)
        (assoc ast :op :invoke
          :invoked (analyze (expr-env env) (first children))
          :args (map (partial analyze (expr-env env)) (rest children))))))

(defn analyze-symbol [env {sym :form :as ast}]
  (cond (:quoted? env) ast
        (= sym (symbol "true")) true-ast-node
        (= sym (symbol "false")) false-ast-node
        (= sym (symbol "nil")) nil-ast-node
        ((set (:locals env)) sym) ast
        :else (assoc ast :form (ceci.env/resolve sym))))

(defn analyze
  ([ast] (analyze {:context :statement :locals [] :quoted? false} ast))
  ([env {:keys [op type] :as ast}]
    (let [ast (assoc ast :env env)]
      (cond (= type :list) (analyze-list env ast)
            (= op :coll) (analyze-coll env ast)
            (= type :symbol) (analyze-symbol env ast)
            :else ast))))
