(ns webnf.cdp-repl.log
  (:require-macros [webnf.cdp-repl.log]))

(.call
 (js/Function.
  #js["global"] "
  function makeLogger(level) {
    return function() {
      var WEBNF_CDP_REPL_RESULT;
      var WEBNF_CDP_REPL_REQUEST = \"webnf.cdp-repl.log/console\";
      var message = Array.from(arguments);
      // preserve level var for inner scope, see
      // https://stackoverflow.com/questions/28388530/why-does-chrome-debugger-think-closed-local-variable-is-undefined/28431346#28431346
      level;
      debugger;
      return null;
    };
  }
  global.console = {
    trace: makeLogger(\"trace\"),
    debug: makeLogger(\"debug\"),
    info: makeLogger(\"info\"),
    log: makeLogger(\"info\"),
    warn: makeLogger(\"warn\"),
    error: makeLogger(\"error\")
  };
")
 nil (js* "this"))
