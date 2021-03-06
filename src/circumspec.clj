(ns circumspec
  (:use [clojure.contrib def pprint str-utils with-ns]
        pattern-match)
  (:import circumspec.ExpectationException
           java.util.regex.Pattern))

(def registered-descriptions (atom []))
(def assertions (atom 0))

(defvar *tests* nil "stack of current tests")

(defmacro wtf
  "'What the form' is going on? Convenience for macroexpand."
  [form]
  `(pprint (macroexpand-1 '~form)))

(defn pps
  "Pretty print into a string"
  [x]
  (with-out-str (pprint x)))

(defnp typeof-is-expression
  [_ s] :when (symbol? s)      :symbol
  [_ _]                        :predicate
  [_ t _] :when (= 'throw t)   :throw
  [_ t _ _] :when (= 'throw t) :throw-with-matcher
  [_ f _] :when (= '= f)       :equality-assertion
  [_ _ _]                      :positive-assertion
  [_ n _ _] :when (= 'not n)   :negative-assertion
  [for & t] :when (= 'for-these for) :for-these-expression
  x                            (throw (RuntimeException.
                                       (apply str "Invalid is form: " x))))              

(defmulti
  reorder
  typeof-is-expression)

(defmethod reorder :throw-with-matcher [[input _ exc str]]
  `(should-throw? ~exc ~str ~input))

(defmethod reorder :throw [[input _ exc]]
  `(should-throw? ~exc ~input))
                
(defmethod reorder :symbol [[input sym]]
  `(should (~(symbol (str (name sym) "?")) ~input)))

(defmethod reorder :predicate [[input predicate]]
  `(should (~predicate ~input)))

(defmethod reorder :positive-assertion [[actual f expected]]
  `(should
    (~f ~actual ~expected)))

(defmethod reorder :equality-assertion [[actual _ expected]]
  `(should-equal ~actual ~expected))

(defmethod reorder :negative-assertion [[actual skipnot f expected]]
  `(should
    (not (~f ~actual ~expected))))

(defmethod reorder :for-these-expression [expression]
  expression)

(def junk-words #{'should 'be})

(defn polish
  "Pronounced 'paulish'"
  [args]
  (reorder (remove junk-words args)))

(defn into-delimited [desc]
  (symbol (re-sub #"\s+" "-" desc)))

(defn rewrite-describe [forms]
  (if (sequential? forms)
   (if (= (first forms) 'describe)
     (cons 'describe-inner (rest forms))
     forms)
   forms))

(defn meta-and-forms
  "Helper that pops off the metadata (if any) and returns [meta, forms]"
  [forms]
  (let [has-meta? (map? (first forms))
        m (if has-meta? (first forms) {})
        forms (if has-meta? (rest forms) forms)]
    [m forms]))


(defmacro describe [desc & its]
  `(describe-outer
    ~desc
    ~@(map rewrite-describe its)))

(defmacro describe-inner [desc & its]
  (let [[m its] (meta-and-forms its)]
    `(with-meta {:type :describe
                 :description ~desc
                 :its (vector ~@its)}
       ~m)))

(defmacro describe-outer [& args]
  `(swap! registered-descriptions conj
          (describe-inner ~@args)))


(defmacro it [desc & forms]
  (let [[m forms] (meta-and-forms forms)]
    `(with-meta {:type :example
                 :description ~desc
                 :forms-and-fns (forms-and-fns ~forms)}
       ~m)))

;; forms are not currently used but might be useful for error reporting
(defmacro forms-and-fns
  "Returns a vector of vectors [[form fn] ...]
   where form is the form and fn is the form
   captured in a fn."
  [forms]
  `(vector ~@(for [f forms]
               (let [p (polish f)]
                 `['~p (fn [] ~p)]))))

(defn print-spaces [n]
  (print (apply str (repeat n "  "))))

(defmulti run-test (fn [{type :type} _ _] type))

(defn print-throwable [throwable]
  (println "  " (.getMessage throwable))
  (comment (doseq [e (.getStackTrace throwable)]
             (println "  " (.toString e)))))

(defmethod run-test :example [{ns-sym :namespace
                               testdesc :description
                               forms-and-fns :forms-and-fns
                               :as test}
                              _ report]
  (print (str "- " testdesc))
  (try
   (binding [*tests* (cons test *tests*)]
     (doseq [[_ fn] forms-and-fns]
       (fn)))
   (println)
   (assoc report :examples (inc (:examples report)))
   (catch Throwable failure
;     (if (instance? failure ExpectationException)
       (do
         (println " (FAILED)")
         (print-throwable failure)
         (assoc report
           :examples (inc (:examples report))
           :failures (inc (:failures report))
           :failure-descriptions (conj (:failure-descriptions report) failure)))
;       (do
;         (println " (ERROR)")
;         (assoc report
;           :examples (inc (:examples report))
;           :errors (inc (:errors report))
;           :error-descriptions (conj (:error-descriptions report) failure)))
;       )
     )))

(defmethod run-test :describe
  [{desc :description tests :its :as test} name-so-far report]
  (println)
  (println (str name-so-far desc))
  (binding [*tests* (cons test *tests*)]
    (reduce
     (fn [report test]
       (run-test test (str name-so-far desc " ") report))
     report
     tests)))

(def empty-report {:examples 0
                   :failures 0
                   :errors 0
                   :failure-descriptions []
                   :error-descriptions []})

(defn output-report [report]
  (println)
  (println
   (str
    @assertions        " assertions, "
    (report :examples) " examples, "
    (report :failures) " failures, "
    (report :errors)   " errors")))

(defn run-tests
  "Run tests, returning true if all pass."
  []
  (reset! circumspec/assertions 0)
  (let [result
        (reduce
         (fn [report describe]
           (run-test describe "" report))
         empty-report
         @registered-descriptions)]
    (output-report result)
    (= (+ (result :failures) (result :errors)) 0)))

(defmulti exception-should-match? (fn [matcher _ _] (class matcher)))

(defmethod exception-should-match? nil [_ _ _] true)

(defmethod exception-should-match? String [expected throwable form]
  (let [actual (.getMessage throwable)]
    (when-not (= expected actual)
      (throw (ExpectationException.
              (str "Expected "
                   form
                   " to throw exception message '"
                   expected
                   "', got '"
                   actual
                   "'"))))))

(defmethod exception-should-match? Pattern [expected throwable form]
  (let [actual (.getMessage throwable)]
    (when-not (re-find expected actual)
      (throw (ExpectationException.
              (str "Expected "
                   form
                   " to throw exception message /"
                   expected
                   "/, got '"
                   actual
                   "'"))))))

(defmacro should-throw?
  "Check to see whether form throws type exception. Possibly also check
   the exception itself with matcher, which can be a string or regexp to
   match against the exception message. Use strings for exact match,
   regexps for fuzzy or partial matches."
  ([ex-type form] `(should-throw? ~ex-type nil ~form))
  ([ex-type matcher form]
      `(when-not (try
                  (do
                    (swap! assertions inc)
                    ~form
                    false)
                  (catch ~ex-type expected#
                    (do
                      (exception-should-match? ~matcher expected# '~form)
                      true))
                  (catch Throwable t#
                    (throw (ExpectationException. (str "Expected " '~form " to throw " ~ex-type ", threw " t#)))))
         (throw (ExpectationException. (str "Expected " '~form " to throw " ~ex-type))))))

(defmacro should-equal [actual expected]
  `(let [actual# ~actual
         expected# ~expected]
     (swap! assertions inc)
     (when-not (= actual# expected#)
       (throw (ExpectationException. (str "Expected\n\n"
                                          (pps '~actual)
                                          "   =\n"
                                          (pps '~expected)
                                          "\ngot\n\n"
                                          (pps actual#)
                                          "   !=\n"
                                          (pps expected#)
                                          "\n"))))))

(defmacro should [assertion]
  `(let [res# ~assertion]
     (swap! assertions inc)
     (if (not res#)
       (throw (ExpectationException. (str '~assertion))))))

(defmacro for-these [names code cmp other & table]
  `(do
     ~@(map
        (fn [args]
          `(should
            (let [~@(interleave names args)] (~cmp ~code ~other))))
        (partition (count names) table))))
