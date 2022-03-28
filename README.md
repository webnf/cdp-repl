This repl env allows to attach a ClojureScript REPL to any running
JavaScript process, that can provide a CDP - compatible
devtools/debugger port.

### Example

#### Start a JS runtime with an inspector port

```sh
node --inspect=9242
```

#### Connect a CLJS REPL

```clj
(defonce OPTS
  {:main 'user
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
(def ENV (repl-env "localhost" 9223 OPTS))
(cljs.repl/repl ENV)
;; or when going via NREPL
#_(cider.piggieback/cljs-repl ENV OPTS)
```

## Embed / Extend

### No runtime requirements

The inspected process need not be compiled with CLJS support, or even
with goog closure support, for that matter. A CDP port is all that's
necessary from the JS runtime.

The goog.base bootstrap is injected on repl setup.

### No network connectivity

Code loading is tunnelled through CDP as well, therefore the REPL
doesn't require any network connectivity. This allows to also repl
into [heavily sandboxed
runtimes](https://github.com/ScreepsMods/screepsmod-user-script-debug).

### Debugger access

The mechanism, that supports blocking tunneling through CDP, is
exposed for extensibility. While cdp-repl is attached, ClojureScript
can make blocking calls into the compiler runtime:

```js
var WEBNF_CDP_REPL_REQUEST = "extension/command";
debugger;
```

`debugger` calls are intercepted by cdp-repl, and for each call where
there is a `WEBNF_CDP_REPL_REQUEST` var in local variables, the
breakpoint is passed to a multimethod:

```clj
(defmethod webnf.cdp-repl/run-command! "extension/command"
  [env connection breakpoint]
  ;; Usually, this could set a result variable,
  ;; to be returned after resuming from the breakpoint
  (clj-chrome-devtools.commands.debugger/resume connection {}))
```
