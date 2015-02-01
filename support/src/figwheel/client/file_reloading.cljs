(ns figwheel.client.file-reloading
  (:require
   [figwheel.client.utils :as utils]
   [goog.Uri :as guri]
   [goog.string]
   [goog.net.jsloader :as loader]
   [clojure.string :as string]
   [cljs.core.async :refer [put! chan <! map< close! timeout alts!] :as async])
  (:require-macros
   [cljs.core.async.macros :refer [go go-loop]]))

;; super tricky hack to get goog to load newly required files

(declare add-cache-buster)

(defn figwheel-closure-import-script [src]
  (if (.inHtmlDocument_ js/goog)
    (do
      (utils/debug-prn (str "Figwheel: latently loading required file " src ))
      (loader/load (add-cache-buster src))
      true)
    false))

(defn patch-goog-base []
  (set! (.-provide js/goog) (.-exportPath_ js/goog))
  (set! (.-CLOSURE_IMPORT_SCRIPT (.-global js/goog)) figwheel-closure-import-script))

;; this assumes no query string on url
(defn add-cache-buster [url] (.makeUnique (guri/parse url)))

(defonce ns-meta-data (atom {}))

(defn ns-to-js-file [ns] (str (string/replace ns "." "/") ".js"))

(defn resolve-ns [ns]
  (str (.-basePath js/goog) "../" (ns-to-js-file ns)))

(def goog-deps-path (str (.-basePath js/goog) "deps.js"))

(defn get-main-file-path
  "Very unreliable way to get the main js file for reloading. 
   Fortunately its not the end of the world if this file doesn't 
   get reloaded."
  []
  (let [sel  (str "script[src='" goog-deps-path "']")]
    (when-let [el (.querySelector js/document sel)]
      (when-let [el (.-nextElementSibling el)]
        (.getAttribute el "src")     ))))

(let [main-file-path (get-main-file-path)]
  (defn resolve-deps-path [file]
    (cond
      (re-matches #".*goog/deps\.js" file) goog-deps-path
      (and main-file-path
           (apply = (mapv #(last (string/split % "/")) [main-file-path file])))
      main-file-path
      :else (do
              (utils/debug-prn (str "No deps match:" file))
              file))))

;; server should add a type to each file
(defn file-type [{:keys [type dependency-file namespace]}]
    (cond
      (= type :css)   :css
      dependency-file :dependency-file
      namespace       :namespace))

(defmulti resolve-url file-type)

(defmethod resolve-url :default [{:keys [file]}] file)

(defmethod resolve-url :dependency-file [{:keys [file]}]
  (resolve-deps-path file))

(defmethod resolve-url :namespace [{:keys [namespace]}]
  (resolve-ns namespace))

(defn reload-file? [{:keys [request-url namespace dependency-file meta-data] :as file-msg}]
  (and 
   (or dependency-file
       (and meta-data (:figwheel-load meta-data))
       ;; IMPORTANT make sure this file is currently provided
       (.isProvided_ js/goog (name namespace)))
   (not (:figwheel-no-load (or meta-data {})))))

(defn js-reload [{:keys [request-url namespace meta-data] :as file-msg} callback]
  (swap! ns-meta-data assoc namespace meta-data)  
  (if (reload-file? file-msg)
    (let [req-url (add-cache-buster request-url)
          _ (utils/debug-prn (str "FigWheel: Attempting to load " req-url))
          deferred (loader/load req-url #js { :cleanupWhenDone true })]
      (.addCallback deferred
                    #(do
                       (utils/debug-prn (str "FigWheel: Successfullly loaded " request-url))
                       (apply callback [(assoc file-msg :loaded-file true)])))
      (.addErrback deferred
                   (fn [eb]
                     (utils/debug-prn (.-stack eb))
                     (.error js/console "Figwheel: Error loading file with script tag:" request-url))))
    (do
      (utils/debug-prn (str "Figwheel: Not trying to load file " request-url))
      (apply callback [file-msg]))))

(defn reload-js-file [file-msg]
  (let [out (chan)]
    (js/setTimeout #(js-reload file-msg (fn [url]
                                          (patch-goog-base)
                                          (put! out url)
                                          (close! out))) 0)
    out))

(defn load-all-js-files
  "Returns a chanel with one collection of loaded filenames on it."
  [files]
  (async/into [] (async/filter< identity (async/merge (mapv reload-js-file files)))))

(defn add-request-url [{:keys [url-rewriter] :as opts} {:keys [file] :as file-msg}]
  (assoc file-msg :request-url
         (if url-rewriter
           (url-rewriter file)
           (resolve-url file-msg))))

(defn add-request-urls [opts files]
  (map (partial add-request-url opts) files))

(defn reload-js-files [{:keys [before-jsload on-jsload load-from-figwheel] :as opts} {:keys [files] :as msg}]
  (go
    (before-jsload files)
    (let [files'  (add-request-urls opts files)
          res'    (<! (load-all-js-files files'))
          res     (filter :loaded-file res')
          files-not-loaded  (filter #(not (:loaded-file %)) res')]
      (when (not-empty res)
        (.debug js/console "Figwheel: loaded these files")
        (.log js/console (pr-str (map (fn [{:keys [namespace file]}]
                                        (if namespace
                                          (ns-to-js-file namespace)
                                          file)) res)))
        (js/setTimeout #(apply on-jsload [res]) 10))
      (when (not-empty files-not-loaded)
        (.debug js/console "Figwheel: NOT loading files that haven't been required")
        (.log js/console "not required:" (pr-str (map :file files-not-loaded)))))))

;; CSS reloading

(defn current-links []
  (.call (.. js/Array -prototype -slice)
         (.getElementsByTagName js/document "link")))

(defn truncate-url [url]
  (-> (first (string/split url #"\?")) 
      (string/replace-first (str (.-protocol js/location) "//") "")
      (string/replace-first ".*://" "")
      (string/replace-first #"^//" "")         
      (string/replace-first #"[^\/]*" "")))

(defn matches-file?
  [{:keys [file]} link]
  (when-let [link-href (.-href link)]
    (let [match (string/join "/"
                         (take-while identity
                                     (map #(if (= %1 %2) %1 false)
                                          (reverse (string/split file "/"))
                                          (reverse (string/split (truncate-url link-href) "/")))))
          match-length (count match)
          file-name-length (count (last (string/split file "/")))]
      (when (>= match-length file-name-length) ;; has to match more than the file name length
        {:link link
         :link-href link-href
         :match-length match-length
         :current-url-length (count (truncate-url link-href))}))))

(defn get-correct-link [f-data]
  (when-let [res (first
                  (sort-by
                   (fn [{:keys [match-length current-url-length]}]
                     (- current-url-length match-length))
                   (keep #(matches-file? f-data %)
                         (current-links))))]
    (:link res)))

(defn clone-link [link url]
  (let [clone (.createElement js/document "link")]
    (set! (.-rel clone)      "stylesheet")
    (set! (.-media clone)    (.-media link))
    (set! (.-disabled clone) (.-disabled link))
    (set! (.-href clone)     (add-cache-buster url))
    clone))

(defn create-link [url]
  (let [link (.createElement js/document "link")]
    (set! (.-rel link)      "stylesheet")
    (set! (.-href link)     (add-cache-buster url))
    link))

(defn add-link-to-doc
  ([new-link]
     (.appendChild (aget (.getElementsByTagName js/document "head") 0)
                   new-link))
  ([orig-link klone]
     (let [parent (.-parentNode orig-link)]
       (if (= orig-link (.-lastChild parent))
         (.appendChild parent klone)
         (.insertBefore parent klone (.-nextSibling orig-link)))
       (js/setTimeout #(.removeChild parent orig-link) 300))))

(defn reload-css-file [{:keys [file request-url] :as f-data}]
  (if-let [link (get-correct-link f-data)]
    (add-link-to-doc link (clone-link link (.-href link)))
    (add-link-to-doc (create-link (or request-url file)))))

(defn reload-css-files [{:keys [on-cssload] :as opts} files-msg]
  (doseq [f (add-request-urls opts (:files files-msg))]
    (reload-css-file f))
  (go
   (<! (timeout 100))
   (on-cssload (:files files-msg))))
