(ns webnf.cdp-repl
  (:require [clj-chrome-devtools.events :as de]
            [clj-chrome-devtools.impl.connection :as dtc]
            (clj-chrome-devtools.commands
             [runtime :as dr]
             [debugger :as dd]
             [console :as dc])

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

(defmacro dochan [[binding chan] & body]
  `(let [chan# ~chan]
     (async/go-loop [val# (async/<! chan#)]
       (when val#
         (let [~binding val#]
           ~@body
           (recur (async/<! chan#)))))))

(defn assoc-once [m k v]
  {:pre [(not (contains? m k))]}
  (assoc m k v))

(defn handle-break! [ctx connection {:as break
                                     {[{:keys [call-frame-id]}] :call-frames} :params}]
  #_(def BREAK break)
  (try
    (match [(dd/evaluate-on-call-frame connection {:call-frame-id call-frame-id
                                                   :expression "WEBNF_CDP_REPL_REQUEST.method"})]
      [{:result {:type "string" :value "read-script"}}]
      (match [(dd/evaluate-on-call-frame connection {:call-frame-id call-frame-id
                                                     :expression "WEBNF_CDP_REPL_REQUEST.script"})]
        [{:result {:type "string" :value script}}]
        (do #_(prn ::read-script script)
         (dd/set-variable-value connection {:call-frame-id call-frame-id
                                            :scope-number 0
                                            :variable-name "WEBNF_CDP_REPL_RESULT"
                                            :new-value {:type "string"
                                                        :value (slurp (io/resource (let [ap (:asset-path ctx)
                                                                                         path (if (str/blank? ap)
                                                                                                script
                                                                                                (str ap "/" script))]
                                                                                     (log/debug "SCRIPT #io/resource" (pr-str path))
                                                                                     path)))}})))
      [{:result {:type "string" :value "console"}}]
      (println (str (->
                     (dd/evaluate-on-call-frame connection {:call-frame-id call-frame-id
                                                            :expression "WEBNF_CDP_REPL_REQUEST.level"})
                     :result :value)
                    ":")
               (->
                (dd/evaluate-on-call-frame connection {:call-frame-id call-frame-id
                                                       :expression "WEBNF_CDP_REPL_REQUEST.values.join(\", \")"})
                :result :value)))
    (dd/resume connection {})
    (catch Exception e
      #_(def ERROR-BREAK break)
      (crepl/pst e))
    #_(finally
        (dd/resume connection {}))))

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
(function(global){
  function makeLogger(level) {
    return function() {
      var WEBNF_CDP_REPL_RESULT;
      var WEBNF_CDP_REPL_REQUEST = {
        method: \"console\",
        level: level,
        values: Array.from(arguments)
      };
      debugger;
      return null;
    };
  }
  global.webnfCdpReplConsole = {
    log: makeLogger(\"log\"),
    warn: makeLogger(\"warn\"),
    error: makeLogger(\"error\")
  };
})(this);

var CLOSURE_BASE_PATH = \"goog/\";
var CLOSURE_UNCOMPILED_DEFINES = " closure-defines ";
var CLOSURE_IMPORT_SCRIPT = (function(global) {
  var sentinel = new Object();
  return function(src) {
    var WEBNF_CDP_REPL_RESULT = sentinel;
    var WEBNF_CDP_REPL_REQUEST = {
      method: \"read-script\",
      script: src
    };
    // this breakpoint triggers the CDP-REPL runtime
    // if you're staring at this in devtools, it means
    // that CDP-REPL is not handling requests
    debugger;
    if(sentinel === WEBNF_CDP_REPL_RESULT) { throw new Error(\"No loader attached\"); }
    global.console = global.webnfCdpReplConsole; // will be overwritten in tick
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

(defrecord DevtoolsEnv [url context handle-break! state]
  clojure.lang.IFn
  (invoke [this k] (get this k))
  (invoke [this k d] (get this k d))
  repl/IJavaScriptEnv
  (-setup [this opts]
    #_(prn ::-setup)
    (let [connection (dtc/connect-url url)
          pl (de/listen connection :debugger :paused)
          cl (de/listen connection :console :message-added)
          rcl (de/listen connection :runtime :execution-context-created)
          rdl (de/listen connection :runtime :execution-context-destroyed)
          ctxs (atom {})]
      (swap! state assoc
             :connection connection
             :debug-pauses pl
             :console-messages cl
             :context-created rcl
             :context-destroyed rdl
             :contexts ctxs)
      (dc/enable connection {})
      (dd/enable connection {})
      (dr/enable connection {})
      (dochan [break pl] (handle-break! this connection break))
      (dochan [msg cl] (prn ::console msg))
      (dochan [{{{:as context :keys [name]} :context} :params} rcl]
              (swap! ctxs assoc-once name context))
      (dochan [{{{:as context :keys [name]} :context} :params} rdl]
              (swap! ctxs dissoc name))
      (dr/evaluate connection
                   {:expression (target this)
                    :context-id context})
      this))
  (-evaluate [_ _ _ js]
    #_(prn ::-evaluate)
    (to-result
     (dr/evaluate (:connection @state)
                  {:expression (str "global.console = global.webnfCdpReplConsole; " js)
                   :context-id context
                   :generate-preview true})))
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
             context 1
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

(comment
  (do
    (defonce OPTS
      {:main 'user
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
    (bapi/build "dev-resources" OPTS)
    (defonce WATCH
      (future (bapi/watch "dev-resources" OPTS)))
    (def ENV (repl-env "localhost" 9223 OPTS))
    (cljs.repl/repl ENV))

  )
