(ns webnf.cdp-repl
  (:require [clj-chrome-devtools.events :as de]
            [clj-chrome-devtools.impl.connection :as dtc]
            (clj-chrome-devtools.commands
             [runtime :as dr]
             [debugger :as dd]
             [console :as dc]
             [target :as dct])

            [clojure.edn :as edn]
            [clojure.repl :as crepl]
            [clojure.core.async :as async]
            [clojure.core.match :as m :refer [match]]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [cljs.closure]
            [cljs.compiler]
            [cljs.build.api :as bapi]

            [cljs.repl :as repl]
            [cljs.util]
            [cljs.stacktrace :as st]
            [clojure.string :as str]))

(defn assoc-once [m k v]
  {:pre [(not (contains? m k))]}
  (assoc m k v))

(defmulti run-command!
  (fn [ctx connection {{[{:keys [call-frame-id]}] :call-frames} :params}]
    (-> connection
        (dd/evaluate-on-call-frame {:call-frame-id call-frame-id
                                    :expression "WEBNF_CDP_REPL_REQUEST"})
        :result :value)))

(defmethod run-command! :default
  [ctx connection {{[{:keys [call-frame-id]}] :call-frames} :params}]
  (log/warn "Breakpoint with undefined/no command"
            (dd/evaluate-on-call-frame connection {:call-frame-id call-frame-id
                                                   :expression "WEBNF_CDP_REPL_REQUEST"})))

(defmethod run-command! "webnf.cdp-repl/asset"
  [ctx connection {{[{:keys [call-frame-id]}] :call-frames} :params}]
  (match [(dd/evaluate-on-call-frame connection {:call-frame-id call-frame-id
                                                 :expression "path"})]
         [{:result {:type "string" :value path}}]
         (do (log/debug ::asset path)
             (dd/set-variable-value connection {:call-frame-id call-frame-id
                                                :scope-number 0
                                                :variable-name "WEBNF_CDP_REPL_RESULT"
                                                :new-value {:type "string"
                                                            :value (slurp (io/resource (let [ap (:asset-path ctx)
                                                                                             path (if (str/blank? ap)
                                                                                                    path
                                                                                                    (str ap "/" path))]
                                                                                         (log/debug "ASSET #io/resource" (pr-str path))
                                                                                         path)))}})))
  (dd/resume connection {}))

(defn handle-break! [ctx connection {:as break
                                     {[{:keys [call-frame-id]}] :call-frames} :params}]
  #_(def BREAK break)
  #_(prn ::handle-break! break)
  (try
    (run-command! ctx connection break)
    (catch Exception e
      #_(def ERROR-BREAK break)
      (crepl/pst e))))

(defn to-result [ret]
  (match [ret]
    [{:result res
      :exception-details details}]
    {:status :exception
     :value (pr-str res)
     :details details}
    [{:result res}]
    {:status :success
     :value (:value res)}))

(defn target [opts]
  (let [module (get (:modules opts) (:module-name opts))
        asset-path (or (:asset-path opts)
                       (cljs.util/output-directory opts))
        closure-defines (json/write-str (:closure-defines opts))]
    (str "
var CLOSURE_BASE_PATH = \"goog/\";
var CLOSURE_UNCOMPILED_DEFINES = " closure-defines ";
var CLOSURE_IMPORT_SCRIPT = (function(global) {
  var sentinel = new Object();
  return function(path) {
    var WEBNF_CDP_REPL_RESULT = sentinel;
    var WEBNF_CDP_REPL_REQUEST = \"webnf.cdp-repl/asset\";
    // this breakpoint triggers the CDP-REPL runtime
    // if you're staring at this in devtools, it means
    // that CDP-REPL is not handling requests
    debugger;
    if(sentinel === WEBNF_CDP_REPL_RESULT) { throw new Error(\"No loader attached\"); }
    eval.call(global, WEBNF_CDP_REPL_RESULT);
    return true;
  };
})(this);
if(typeof goog == 'undefined') {
  CLOSURE_IMPORT_SCRIPT(\"goog/base.js\");
}
CLOSURE_IMPORT_SCRIPT(\"cljs_deps.js\");
"
         (apply str (cljs.closure/preloads (:preloads opts)))
         (apply str
                (map (fn [entry]
                       (when-not (= "goog" entry)
                         (str "goog.require(\"" (cljs.compiler/munge entry) "\");\n")))
                     (if-let [entries (when module (:entries module))]
                       entries
                       (when-let [main (:main opts)]
                         [main])))))))

(defn assoc-if [m k v]
  (if v (assoc m k v) m))

(defrecord DevtoolsEnv [url context handle-break! state]
  clojure.lang.IFn
  (invoke [this k] (get this k))
  (invoke [this k d] (get this k d))
  repl/IJavaScriptEnv
  (-setup [this opts]
    #_(prn ::-setup)
    (let [connection (dtc/connect-url url)
          pl #(handle-break! this connection %)
          cl (fn [{:as evt {{:keys [level text] :as msg} :message} :params}]
               #_(prn ::console evt)
               (try
                 (log/log (case level
                            "log" :info
                            "warning" :warn
                            (keyword level))
                          text)
                 (catch Exception e
                   (log/fatal e "While logging" msg))))
          ctxs (atom {})
          rcl (fn [{{{:as context :keys [name]} :context} :params}]
                (swap! ctxs assoc-once name context))
          rdl (fn [{{{:as context :keys [name]} :context} :params}]
                (swap! ctxs dissoc name))]
      (swap! state assoc
             :connection connection
             :debug-pauses pl
             :console-messages cl
             :context-created rcl
             :context-destroyed rdl
             :contexts ctxs)
      (de/listen connection :debugger :paused pl)
      (de/listen connection :console :message-added cl)
      (de/listen connection :runtime :execution-context-created rcl)
      (de/listen connection :runtime :execution-context-destroyed rdl)

      (dc/enable connection {})
      (dd/enable connection {})
      (dr/enable connection {})
      (dr/evaluate connection
                   (assoc-if {:expression (target this)}
                             :context-id context))
      this))
  (-evaluate [_ _ _ js]
    #_(prn ::-evaluate)
    (to-result
     (dr/evaluate (:connection @state)
                  (assoc-if {:expression js
                             :generate-preview true}
                            :context-id context))))
  (-load [_ provides url]
    #_(prn ::-load)
    (let [{:keys [connection]} @state
          {:keys [script-id]} (dr/compile-script
                               connection
                               {:expression (slurp url)
                                :source-url url
                                :execution-context-id context
                                :persist-script true})]
      (to-result
       (dr/run-script connection
                      {:script-id script-id
                       :execution-context-id context
                       :generate-preview true}))))
  (-tear-down [this]
    #_(prn ::-tear-down)
    (let [{:keys [connection debug-pauses console-messages context-created context-destroyed]} @state]
      (if connection
        (do
          (dd/disable connection {})
          (dc/disable connection {})
          (dr/disable connection {})
          (de/unlisten connection :debugger :paused debug-pauses)
          (de/unlisten connection :console :message-added console-messages)
          (de/unlisten connection :runtime :execution-context-created context-created)
          (de/unlisten connection :runtime :execution-context-destroyed context-destroyed)
          (.close connection))
        (println "No connection, proceeding with shutdown"))
      (swap! state dissoc :debug-pauses :console-messages :context-created :context-destroyed :connection)
      this))
  repl/IReplEnvOptions
  (-repl-options [this]
    (assoc this ::repl/fast-initial-prompt? :after-setup))
  repl/IParseStacktrace
  (-parse-stacktrace [this st err opts]
    (st/parse-stacktrace this st err opts))
  repl/IGetError
  (-get-error [this e env opts]
    (edn/read-string
     (repl/evaluate-form this env "<cljs repl>"
                         `(when ~e
                            (pr-str
                             {:ua-product (clojure.browser.repl/get-ua-product)
                              :value (str ~e)
                              :stacktrace (.-stack ~e)}))))))

(defn repl-env*
  [url {:as opts :keys [context handle-break! target-fn]
        :or {handle-break! #'handle-break!
             target-fn `target}}]
  (map->DevtoolsEnv
   (merge {:url url
           :context context
           :handle-break! handle-break!
           :state (atom {})
           :target-fn target-fn}
          opts)))

(defn repl-env
  [host port {:as opts :keys [::page]
              :or {page 0}}]
  (-> (dtc/inspectable-pages host port)
      (nth page) :web-socket-debugger-url
      (repl-env* (assoc opts ::host host ::port port ::page page))))

(defn test-env-repl []
  ;; chromium --remote-debugging-port=9222
  (def OPTS
    {:main 'user
     ;; :context 57
     ;; :preloads '[webnf.cdp-repl.log]
     :asset-path "cdp-repl"
     :output-to "target/resources/cdp-repl.js"
     :output-dir "target/resources/cdp-repl/"
     :target :bundle
     :optimizations :none
     :pretty-print true
     :source-map true
     :parallel-build true
     :aot-cache true
     :infer-externs true})
  (bapi/build (bapi/inputs "src" "dev-resources") OPTS)
  (defonce WATCH
    (future (bapi/watch (bapi/inputs "src" "dev-resources") OPTS)))
  (def ENV (repl-env "127.0.0.1" 9222 OPTS))
  (binding [cljs.repl/*cljs-verbose* true]
    #_(cljs.repl/repl ENV)
    (cider.piggieback/cljs-repl ENV)))

(comment

  (dtc/inspectable-pages "localhost" 9222)
  (def conn (dtc/connect "127.0.0.1" 9222))
  (dr/evaluate conn {:expression "alert('hi')"})
  (dct/get-targets conn {})
  (de/listen conn :runtime :execution-context-created (comp prn (juxt :event :params)))
  (de/listen conn :runtime :execution-context-destroyed (comp prn (juxt :event :params)))
  (dr/enable conn {})
  (.close conn)

  )
