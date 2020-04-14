(ns com.fulcrologic.fulcro.react.hooks
  "Simple wrappers for React hooks support, along with additional predefined functions that do useful things
   with hooks in the context of Fulcro."
  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  ;; WARNING TO MAINTAINERS: DO NOT REFERENCE DOM IN HERE. This has to work with native.
  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  (:require
    [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
    #?(:cljs
       [goog.object :as gobj]
       cljsjs.react)
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.application :as app]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.rendering.multiple-roots-renderer :as mrr]
    [com.fulcrologic.fulcro.algorithms.normalized-state :as fns]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]))

(defn use-state
  "A simple wrapper around React/useState. Returns a cljs vector for easy destructuring.

  React docs: https://reactjs.org/docs/hooks-reference.html#usestate"
  [initial-value]
  #?(:cljs (into-array (js/React.useState initial-value))))

(defn use-effect
  "A simple wrapper around React/useEffect.

  React docs: https://reactjs.org/docs/hooks-reference.html#useeffect"
  ([f]
   #?(:cljs (js/React.useEffect f)))
  ([f args]
   #?(:cljs (js/React.useEffect f (to-array args)))))

(defn use-context
  "A simple wrapper around React/useContext."
  [ctx]
  #?(:cljs (js/React.useContext ctx)))

(defn use-reducer
  "A simple wrapper around React/useReducer. Returns a cljs vector for easy destructuring

  React docs: https://reactjs.org/docs/hooks-reference.html#usecontext"
  ([reducer initial-arg]
   #?(:cljs (into-array (js/React.useReducer reducer initial-arg))))
  ([reducer initial-arg init]
   #?(:cljs (into-array (js/React.useReducer reducer initial-arg init)))))

(defn use-callback
  "A simple wrapper around React/useCallback. Converts args to js array before send.

  React docs: https://reactjs.org/docs/hooks-reference.html#usecallback"
  ([cb]
   #?(:cljs (js/React.useCallback cb)))
  ([cb args]
   #?(:cljs (js/React.useCallback cb (to-array args)))))

(defn use-memo
  "A simple wrapper around React/useMemo. Converts args to js array before send.

  React docs: https://reactjs.org/docs/hooks-reference.html#usememo"
  ([cb]
   #?(:cljs (js/React.useMemo cb)))
  ([cb args]
   #?(:cljs (js/React.useMemo cb (to-array args)))))

(defn use-ref
  "A simple wrapper around React/useRef.

  React docs: https://reactjs.org/docs/hooks-reference.html#useref"
  ([] #?(:cljs (js/React.useRef nil)))
  ([value] #?(:cljs (js/React.useRef value))))

(defn use-imperative-handle
  "A simple wrapper around React/useImperativeHandle.

  React docs: https://reactjs.org/docs/hooks-reference.html#useimperativehandle"
  [ref f]
  #?(:cljs (js/React.useImperativeHandle ref f)))

(defn use-layout-effect
  "A simple wrapper around React/useLayoutEffect.

  React docs: https://reactjs.org/docs/hooks-reference.html#uselayouteffect"
  ([f]
   #?(:cljs (js/React.useLayoutEffect f)))
  ([f args]
   #?(:cljs (js/React.useLayoutEffect f (to-array args)))))

(defn use-debug-value
  "A simple wrapper around React/useDebugValue.

  React docs: https://reactjs.org/docs/hooks-reference.html#uselayouteffect"
  ([value]
   #?(:cljs (js/React.useDebugValue value)))
  ([value formatter]
   #?(:cljs (js/React.useDebugValue value formatter))))

(let [id (fn [] (tempid/uuid))]
  (defn use-generated-id
    "Returns a constant ident with a generated ID component."
    []
    (let [[id _] (use-state id)]
      id)))

(defn use-gc
  "Effect handler. Creates an effect that will garbage-collect the given ident from fulcro app state on cleanup, and
  will follow any `edges` (a set of keywords) and remove any things pointed through those keywords as well. See
  normalized-state's `remove-entity`.

  ```
  (defsc NewRoot [this props]
    {:use-hooks? true}
    (let [generated-id (hooks/use-generated-id)
          f (use-fulcro-mount this {:child-class SomeChild
                                    :initial-state-params {:id generated-id})]
      ;; will garbage-collect the floating root child on unmount
      (use-gc this [:child/id generated-id] #{})
      (f props)))
  ```
  "
  [this-or-app ident edges]
  (use-effect (fn []
                ;; nothing to do on start
                (fn []
                  (let [state (comp/any->app this-or-app)]
                    (fns/remove-ident)))
                #?(:cljs #js []))))

(let [initial-mount-state (fn []
                            (let [componentName (keyword "com.fulcrologic.fulcro.floating-root" (gensym "generated-root"))]
                              #?(:clj [componentName nil] :cljs #js [componentName nil])))]
  (defn use-fulcro-mount
    "
    Generate a new sub-root.

    ```
    ;; important, you must use hooks (`defhc` or `:use-hooks? true`)
    (defsc NewRoot [this props]
      {:use-hooks? true}
      (let [f (use-fulcro-mount this {:child-class SomeChild})]
        ;; parent props will show up in SomeChild as computed props.
        (f props)))
    ```

    WARNING: Requires you use multi-root-renderer."
    [parent-this {:keys [child-class
                         initial-state-params]}]
    ;; factories are functions, and if you pass a function to setState it will run it, which is NOT what we want...
    (let [[key-and-root setRoot!] (use-state initial-mount-state)
          _ (use-effect
              (fn []
                (let [join-key      (aget key-and-root 0)
                      child-factory (comp/computed-factory child-class)
                      cls           (comp/configure-hooks-component!
                                      (fn [this fulcro-props]
                                        (log/spy :info "RENDER")
                                        (use-effect
                                          (fn []
                                            (log/info "REGISTER")
                                            (mrr/register-root! this)
                                            (fn []
                                              (log/info "DEREGISTER")
                                              (mrr/deregister-root! this)))
                                          #?(:cljs #js []))
                                        (comp/with-parent-context parent-this
                                          (child-factory (get fulcro-props join-key) (comp/props parent-this))))
                                      {:query         (fn [_] [{join-key (comp/get-query child-class)}])
                                       :initial-state (fn [_] {join-key (comp/get-initial-state child-class (or initial-state-params {}))})
                                       :componentName join-key})
                      factory       (comp/computed-factory cls {:keyfn (fn [_] join-key)})]
                  (log/debug "Creating floating root class" join-key)
                  (setRoot! #?(:clj [join-key factory] :cljs #js [join-key factory])))
                (fn []
                  (let [join-key (aget key-and-root 0)
                        state    (-> parent-this comp/any->app ::app/state-atom)]
                    (log/debug "Cleaning up floating root class" join-key)
                    (swap! state dissoc join-key))))
              #?(:cljs #js []))]
      (aget key-and-root 1))))
