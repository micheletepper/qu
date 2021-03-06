(ns cfpb.qu.where
  (:require
   [clojure.string :as str]
   [protoflex.parse
    :as p
    :refer [expr eval-expr-tree
            any attempt multi* series
            number dq-str sq-str chr
            word-in regex starts-with?]]))

;; TODO support NOT in mongo
;; TODO support functions

(defn numeric-expr []
  (let [expr (expr)]
    (eval-expr-tree expr)))

(defn numeric []
  (any numeric-expr number))

(defn strexp []
  (any dq-str sq-str))

(defn value []
  (any numeric strexp))

(defn comp-op []
  (let [op (word-in [">" ">=" "=" "!=" "<" "<="])]
    (keyword op)))

(defn identifier []
  (let [ident (regex #"[A-Za-z][A-Za-z0-9\-_]*")]
    (keyword ident)))

(defn comparison []
  (let [identifier (identifier)
        op (comp-op)
        value (value)]
    {:comparison [identifier op value]}))

(defn and-or-op []
  (let [op (word-in ["AND" "OR" "and" "or"])]
    (keyword (str/upper-case op))))

(defn not-op []
  (let [op (word-in ["NOT" "not"])]
    (keyword (str/upper-case op))))

(declare where-expr)

(defn paren-where-expr []
  (chr \()
  (let [expr (where-expr)]
    (chr \))
    expr))

(defn boolean-factor []
  (let [not-op (attempt not-op)
        factor (if (starts-with? "(")
                 (paren-where-expr)
                 (comparison))]
    (if not-op
      {:not factor}
      factor)))

(defn- build-boolean-tree [nodes]
  (let [nc (count nodes)]
    (assert (and
             (>= nc 3)
             (odd? nc)))
    (if (= nc 3)
      {:left (nth nodes 0) :op (nth nodes 1) :right (nth nodes 2)}
      {:left (build-boolean-tree (take (- nc 2) nodes))
       :op (nodes (- nc 2))
       :right (nodes (- nc 1))})))

(defn where-expr []
  (if-let [left (attempt boolean-factor)]
    (if-let [rhs (multi* #(series and-or-op boolean-factor))]
      (do
        (-> (into [left] (apply concat rhs))
            build-boolean-tree))
      left)))

(defn parse [clause]
  (p/parse where-expr clause))

(def mongo-ops
  {:AND "$and"
   :OR "$or"
   :< "$lt"
   :<= "$lte"
   :> "$gt"
   :>= "$gte"
   :!= "$ne"})

(def mongo-opposites
  {"$lt" "$gte"
   "$lte" "$gt"
   "$gt" "$lte"
   "$gte" "$lt"
   "$and" "$or"})

(declare mongo-eval-not)

(defn mongo-eval [ast]
  (cond
   (get ast :not)
   (mongo-eval-not (:not ast))
   
   (get ast :op)
   (let [{:keys [op left right]} ast]
     {(op mongo-ops) [(mongo-eval left) (mongo-eval right)]})

   (get ast :comparison)
   (let [[ident op value] (:comparison ast)]
     (if (= op :=)
       {ident value}
       {ident {(op mongo-ops) value}}))))

(defn mongo-eval-not [ast]
  (cond
   (get ast :not)
   (mongo-eval (:not ast))
   
   (get ast :op)
   (let [{:keys [op left right]} ast]
     (case op
       :OR {"$nor" [(mongo-eval left) (mongo-eval right)]}
       :AND {"$or" [(mongo-eval-not left) (mongo-eval-not right)]}))

   (get ast :comparison)
   (let [[ident op value] (:comparison ast)]
     (case op
       :!= {ident value}
       := {ident {"$ne" value}}
       {ident {(mongo-opposites (op mongo-ops)) value}}))))
