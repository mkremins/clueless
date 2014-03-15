(ns ceci.emitter
  (:require [clojure.string :as string]))

(declare emit)

(defn emit-escaped [s]
  (-> (name s)
    (string/replace #"\+" "_PLUS_")
    (string/replace #"-" "_")
    (string/replace #"\*" "_STAR_")
    (string/replace #"/" "_SLASH_")
    (string/replace #"\?" "_QMARK_")
    (string/replace #"!" "_BANG_")
    (string/replace #"<" "_LT_")
    (string/replace #">" "_GT_")
    (string/replace #"=" "_EQ_")))

(defn emit-statements [statements]
  (string/join (map emit statements)))

(defn emit-wrapped [& exprs]
  (str "(function(){" (string/join exprs) "})()"))

(defn comma-sep [args]
  (->> args (map emit) (string/join ",")))

;; special forms

(defn emit-aget [{:keys [target fields]}]
  (letfn [(emit-field [field] (str "[" (emit field) "]"))]
    (apply str (emit target) (map emit-field fields))))

(defn emit-aset [{:keys [value] :as ast}]
  (str (emit-aget ast) "=" (emit value)))

(defn emit-def [{:keys [name init]}]
  (str (emit name) "=" (emit init)))

(defn emit-do [{:keys [env body]}]
  (if (= (:context env) :expr)
      (emit-wrapped (emit-statements body))
      (emit-statements body)))

(defn emit-if [{:keys [env test then else]}]
  (if (= (:context env) :expr)
      (str "((" (emit test) ")?" (emit then) ":" (emit else) ")")
      (str "if(" (emit test) "){" (emit then)
           "}else{" (emit else) "}")))

(defn emit-new [{:keys [ctor args]}]
  (str "(new " (emit ctor) "(" (comma-sep args) "))"))

(defn emit-throw [{:keys [thrown]}]
  (emit-wrapped "throw " (emit thrown) ";\n"))

;; function forms

(defn emit-params [params]
  (when-not (empty? params)
    (str (->> (range (count params))
              (map (fn [param-num]
                     (str (emit-escaped (get params param-num))
                          "=arguments[" param-num "]")))
              (string/join ";\n")) ";\n")))

(defn emit-fn-clause [[num-params {:keys [params body]}]]
  (str "case " num-params ":" (emit-params params)
       "return " (emit-statements body)))

(defn emit-fn [{:keys [clauses]}]
  (if (= (count clauses) 1)
      (let [{:keys [params body]} (val (first clauses))]
        (str "(function(){" (emit-params params)
             (emit-statements body) "})"))
      (str "(function(){switch(arguments.length){"
           (string/join ";\n" (map emit-fn-clause clauses))
           ";\ndefault:throw new Error("
           "\"invalid function arity (\" + arguments.length + \")\""
           ");}})")))

;; let, loop and recur forms

(defn emit-bindings [bindings]
  (when (> (count bindings) 0)
    (str (->> bindings
              (map (fn [[k v]] (str (emit-escaped k) "=" (emit v))))
              (string/join ";\n")) ";\n")))

(defn emit-let [{:keys [env bindings body]}]
  (if (= (:context env) :expr)
      (emit-wrapped (emit-bindings bindings) (emit-statements body))
      (str (emit-bindings bindings) (emit-statements body))))

(defn emit-loop [{:keys [env bindings body]}]
  (if (= (:context env) :expr)
      (emit-wrapped (emit-bindings bindings)
                    "while(true){"
                    (emit-statements body)
                    "break;\n}")
      (str (emit-bindings bindings) "while(true){"
           (emit-statements body) "break;\n}")))

(defn emit-recur [{:keys [args recur-point] :as ast}]
  (let [recur-bindings (:bindings recur-point)
        bindings
        (loop [bindings [] idx 0]
          (let [[binding _] (get recur-bindings idx)
                arg (get args idx)]
            (if (and binding arg)
                (recur (conj bindings [binding arg]) (inc idx))
                bindings)))]
    (str (emit-bindings bindings) "continue")))

;; list forms

(defn emit-fncall [callable args]
  (str (emit callable) ".call(null," (string/join "," (map emit args)) ")"))

(defn emit-list [{:keys [children] {:keys [quoted?]} :env}]
  (if-let [{:keys [type value] :as first-child} (first children)]
    (if quoted?
        (str "cljs.core.list(" (comma-sep children) ")")
        (emit-fncall first-child (rest children)))
    "cljs.core.List.EMPTY"))

;; other forms

(defn emit-vector [{:keys [children]}]
  (if (empty? children)
      "cljs.core.PersistentVector.EMPTY"
      (str "cljs.core.PersistentVector.fromArray(["
           (comma-sep children) "],true)")))

(defn emit-map [{:keys [children]}]
  (if (empty? children)
      "cljs.core.PersistentArrayMap.EMPTY"
      (str "new cljs.core.PersistentArrayMap.fromArray(["
           (comma-sep children) "],true,false)")))

(defn emit-set [{:keys [children]}]
  (if (empty? children)
      "cljs.core.PersistentHashSet.EMPTY"
      (str "cljs.core.PersistentHashSet.fromArray(["
           (comma-sep children) "],true)")))

(defn emit-number [{:keys [form]}]
  (str form))

(defn emit-keyword [{:keys [form]}]
  (let [name (name form)]
    (str "new cljs.core.Keyword(null,\""
         name "\",\"" name "\"," (hash form) ")")))

(defn emit-string [{:keys [form]}]
  (str "\"" form "\""))

(defn emit-symbol [{:keys [form] {:keys [quoted?]} :env}]
  (let [ns (namespace form)
        name (name form)]
    (if quoted?
        (str "new cljs.core.Symbol("
             (if ns (str "\"" ns "\"") "null") ",\"" name "\",\""
             (str (when ns (str ns ".")) name) "\"," (hash form) ",null)")
        (str (when (and ns (not= ns "js")) (str (emit-escaped ns) "."))
             (emit-escaped name)))))

(defn emit-bool [{:keys [form]}]
  (str form))

(defn emit-nil [_]
  "null")

;; generic interface

(def emitters
  {:list emit-list
   :vector emit-vector
   :map emit-map
   :set emit-set
   :number emit-number
   :keyword emit-keyword
   :string emit-string
   :symbol emit-symbol
   :bool emit-bool
   :nil emit-nil
   :aget emit-aget
   :aset emit-aset
   :def emit-def
   :do emit-do
   :fn emit-fn
   :if emit-if
   :let emit-let
   :loop emit-loop
   :new emit-new
   :recur emit-recur
   :throw emit-throw})

(defn emit [{:keys [env op type] :as ast}]
  (let [context (:context env)
        return? (and (= context :return)
                     (#{:aget :aset :const :coll :fn :new} op))]
    (str (when return? "return ")
         (if (#{:const :coll} op)
             (let [emit-type (emitters type)]
               (emit-type ast))
             (let [emit-op (emitters op)]
               (emit-op ast)))
         (when-not (or (= context :expr)
                       (#{:if :let :loop} op)) ";\n"))))
 