(ns webnf.cdp-repl.log
  (:require [clj-chrome-devtools.commands.debugger :as dd]
            [clojure.tools.logging :as log]
            [webnf.cdp-repl :as core]))

(defmethod core/run-command! "webnf.cdp-repl.log/console"
  [ctx connection {{[{:keys [call-frame-id]}] :call-frames} :params}]
  (log/log
   (->
    (dd/evaluate-on-call-frame connection {:call-frame-id call-frame-id
                                           :expression "level"})
    :result :value keyword)
   (->
    (dd/evaluate-on-call-frame connection {:call-frame-id call-frame-id
                                           :expression "message.join(\", \")"})
    :result :value))
  (dd/resume connection {}))
