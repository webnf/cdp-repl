(ns webnf.cdp-repl
  (:require [clj-chrome-devtools.core :as dt]
            [clj-chrome-devtools.events :as de]
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
            [cljs.build.api :as bapi]

            [cljs.repl :as repl]
            [cljs.stacktrace :as st]
            [clj-http.client :as hc]))

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

#_(def c (dtc/connect-url "ws://127.0.0.1:7777/inspect/e53a2171f13dfbe"))
#_(dt/set-current-connection! c)
#_(.close c)

#_(def ctxs (atom {}))
#_(dochan [{{:keys [context]} :params}
         (de/listen c :runtime :execution-context-created)]
        (swap! ctxs assoc-once (:name context) context))
#_(dr/enable c {})

#_(dr/get-properties {:object-id (:object-id (:result (dr/evaluate {:expression "global" :context-id 2})))})

#_(dr/evaluate c {:expression "throw '';" :context-id 2})

#_(dr/compile-script c {:expression "debugger"
                      :source-url "<input>"
                      :execution-context-id 2
                      :persistScript true})
#_(dr/run-script c {:script-id "115"
                  :execution-context-id 2
                  :return-by-value false
                  :generate-preview true})

(defn handle-break! [connection {:as break
                                 {[{:keys [call-frame-id]}] :call-frames} :params}]
  (def BREAK break)
  (try
    (match [(dd/evaluate-on-call-frame connection {:call-frame-id call-frame-id
                                                   :expression "CLJS_DEVTOOLS_REQUEST.method"})]
           [{:result {:type "string" :value "read-script"}}]
           (match [(dd/evaluate-on-call-frame connection {:call-frame-id call-frame-id
                                                          :expression "CLJS_DEVTOOLS_REQUEST.script"})]
                  [{:result {:type "string" :value script}}]
                  (do (prn :read-script script)
                      (dd/set-variable-value connection {:call-frame-id call-frame-id
                                                         :scope-number 0
                                                         :variable-name "CLJS_DEVTOOLS_RESULT"
                                                         :new-value {:type "string"
                                                                     :value (slurp (or (io/resource (str "bot/" script))
                                                                                       (io/resource script)))}})))
           [{:result {:type "string" :value "console"}}]
           (println (str (->
                          (dd/evaluate-on-call-frame connection {:call-frame-id call-frame-id
                                                                 :expression "CLJS_DEVTOOLS_REQUEST.level"})
                          :result :value)
                         ":")
                    (->
                     (dd/evaluate-on-call-frame connection {:call-frame-id call-frame-id
                                                            :expression "CLJS_DEVTOOLS_REQUEST.values.join(\", \")"})
                     :result :value)))
    (dd/resume connection {})
    (catch Exception e
      (def ERROR-BREAK break)
      (crepl/pst e))
    (finally
      #_(dd/resume connection {}))))

#_(dochan [break (de/listen :debugger :paused)]
        (handle-break! c break))
#_(dd/enable)

#_(dochan [msg
         (de/listen c :console :message-added)]
        (println "Console Message")
        (prn :msg msg))
#_(dc/enable c {})

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

(defn bootstrap [opts]
  (let [module (get (:modules opts) (:module-name opts))
        asset-path (or (:asset-path opts)
                       (cljs.util/output-directory opts))
        closure-defines (clojure.data.json/write-str (:closure-defines opts))]
    (str "
(function(global){
  function makeLogger(level) {
    return function() {
      var CLJS_DEVTOOLS_RESULT;
      var CLJS_DEVTOOLS_REQUEST = {
        method: \"console\",
        level: level,
        values: Array.from(arguments)
      };
      debugger;
      return null;
    };
  }
  global.devtoolsConsole = {
    log: makeLogger(\"log\"),
    warn: makeLogger(\"warn\"),
    error: makeLogger(\"error\")
  };
  global.screepsConsole = global.console;
})(this);

var CLOSURE_BASE_PATH = \"goog/\";
var CLOSURE_UNCOMPILED_DEFINES = " closure-defines ";
var CLOSURE_IMPORT_SCRIPT = (function(global) {
  var sentinel = new Object();
  return function(src) {
    var CLJS_DEVTOOLS_RESULT = sentinel;
    var CLJS_DEVTOOLS_REQUEST = {
      method: \"read-script\",
      script: src
    };
    debugger;
    if(sentinel === CLJS_DEVTOOLS_RESULT) { throw new Error(\"No loader attached\"); }
    global.console = global.devtoolsConsole; // will be overwritten in tick
    eval.call(global, CLJS_DEVTOOLS_RESULT);
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
  repl/IJavaScriptEnv
  (-setup [this opts]
    (prn :setup)
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
      (dochan [break pl] (handle-break! connection break))
      (dochan [msg cl] (prn :console msg))
      (dochan [{{{:as context :keys [name]} :context} :params} rcl]
              (swap! ctxs assoc-once name context))
      (dochan [{{{:as context :keys [name]} :context} :params} rdl]
              (swap! ctxs dissoc name))
      (dr/evaluate connection
                   {:expression (bootstrap this)
                    :context-id context})
      this))
  (-evaluate [_ _ _ js]
    (to-result
     (dr/evaluate (:connection @state)
                  {:expression (str "global.console = global.devtoolsConsole; " js)
                   :context-id context
                   :generate-preview true})))
  (-load [_ provides url]
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
    (prn :tear-down)
    (let [{:keys [connection debug-pauses console-messages context-created context-destroyed]} @state]
      (dd/disable connection {})
      (dc/disable connection {})
      (dr/disable connection {})
      (de/unlisten connection :debugger :paused debug-pauses)
      (de/unlisten connection :console :message-added console-messages)
      (de/unlisten connection :runtime :execution-context-created context-created)
      (de/unlisten connection :runtime :execution-context-destroyed context-destroyed)
      (.close connection)
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
  [{:as opts :keys [url context handle-break!]
    :or {handle-break! #'handle-break!
         context 0}}]
  (merge (->DevtoolsEnv url context handle-break! (atom {}))
         opts))

(comment
  (future
    (bapi/build
     "src"
     {:main "screeps.bot"
      :asset-path "bot"
      :output-to "target/resources/bot.js"
      :output-dir "target/resources/bot/"
      :target :bundle
      :target-fn `bootstrap
      :optimizations :none
      :pretty-print true
      :source-map true
      :parallel-build true
      :aot-cache true
      :infer-externs true
      :verbose false}))
  )

(comment
  (cider.piggieback/cljs-repl
   (repl-env* {:url "ws://127.0.0.1:7777/inspect/e53a2171f13dfbe"
               :context 2
               ;; :entry-point "bot.js"
               ;; :working-dir "target/resources/bot/"
              
               :main "screeps.bot"
               :asset-path "bot"
               :output-to "target/resources/bot.js"
               :output-dir "target/resources/bot/"
               :target :bundle
               :target-fn `bootstrap
               :optimizations :none
               :pretty-print true
               :source-map true
               :parallel-build true
               :aot-cache true
               :infer-externs true}))
  )

(comment
 (hc/get "https://screeps.com/api/auth/me"
         {:headers {"X-Token" "a4qb9edcd-f68d-428c-8ae0-0c00f23931e1"}})

 (def login-token
   (->
    (hc/post "http://localhost:21025/api/auth/signin"
             {:headers {:accept "application/json"
                        :content-type "application/json"}
              :body (json/json-str {:email "bendlas"
                                    :password "steyr"})})
    :body
    (json/read-str :key-fn keyword)
    :token))

 (->
  (hc/get "http://localhost:21025/api/auth/me"
          {:headers {"X-Token" login-token
                     "X-Username" login-token}}
          #_{:basic-auth ["bendlas" "steyr"]}
          #_{:headers {"authorization" (hc/basic-auth-value ["bendlas" "steyr"])}})
  :body
  (json/read-str :key-fn keyword))

 (hc/get "http://localhost:21025/api/user/code"
         {:as :json
          :headers {"X-Token" login-token
                    "X-Username" login-token}})

 (hc/post "http://localhost:21025/api/user/code"
          {:as :json
           :headers {"X-Token" login-token
                     "X-Username" login-token}
           :accept :json
           :content-type :json
           :body (json/json-str
                  {:branch "default"
                   :modules
                   {:main "
module.exports.loop = function () {
  if (undefined === global.CLJS_LOOP) {
    console.log(\"Waiting for CLJS_LOOP to be defined\");
  } else {
    CLJS_LOOP();
  }
}
"}})})
 )
