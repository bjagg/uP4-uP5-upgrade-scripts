#!/usr/bin/env lein-exec

;;
;; For a given directory containing portlet definition files,
;; filter down to those of 'single-feed-news'
;; and create both a predefined-news datafile (pnd.xml)
;; and update the portlet definition in place.
;;

(use '[leiningen.exec :only (deps)])
(deps '[[org.clojure/data.xml "0.0.8"]
        [org.clojure/data.zip "1.0.0"]])

(require '(clojure [zip :as zip :only (xml-zip children end? root next branch? edit remove node insert-left up right)]
                   [string :refer (replace-first ends-with?)]
                   [pprint :refer (pprint)]
                   [xml :refer (emit parse element emit-element)])
         '(clojure.java [io :refer (as-file file input-stream)])
         ;'(clojure.data [xml :as xml]) -- parse and emit from this package should be better than clojure.xml, but they aren't
         '(clojure.data.zip [xml :refer (text xml-> xml1->)]))

(import '[javax.xml.parsers SAXParserFactory])

;;
;; Read the directories passed as arguments
;;

(def data-dir (as-file (nth *command-line-args* 1)))
(def news-dir (as-file (nth *command-line-args* 2)))

(defn check-dir-exists [dir-as-file desc]
  (if (and (.exists dir-as-file) (.isDirectory dir-as-file))
    (println "Directory" dir-as-file "found for" desc)
    (do
      (println "Directory" dir-as-file "for" desc "could not be found! - exting ...")
      (System/exit 1))))

(check-dir-exists data-dir "portlet-definitions")
(check-dir-exists news-dir "predefined news")

;;
;; Collect data files
;;

(def data-files (.listFiles data-dir
                            (reify
                              java.io.FileFilter
                              (accept [this f]
                                (ends-with? (.getName f) ".xml")))))
;(pprint data-files)

;;
;; Parse file into a xml zipper
;;

(defn non-validating [s ch]
  (..
    (doto
      (SAXParserFactory/newInstance)
      (.setFeature 
        "http://apache.org/xml/features/nonvalidating/load-external-dtd" false))
    (newSAXParser)
    (parse s ch)))

(defn parse-as-zip
  [file]
  (-> file
      (parse non-validating)
      zip/xml-zip))

;(println (parse-as-zip (first data-files)))

;;
;; filter portlet definitions to single-feed-news

(def sfn-portlet-name "single-feed-news")

(defn is-sfn?
  "Is the portlet definition file a single-feed-news def"
  [filename]
  (let [xml (parse-as-zip filename)
        ;portlet-name (text (xml1-> xml :portlet-definition :portlet-descriptor :ns2:portletName))]
        portlet-name (xml1-> xml :portlet-definition :portlet-descriptor :ns2:portletName text)]
    ;(println portlet-name)
    (= sfn-portlet-name portlet-name)))

(def sfn-files (filter is-sfn? data-files))
;(pprint sfn-files)

(defn get-url-pref-el [zipper]
  (let [prefs (xml-> zipper :portlet-definition :portlet-preference)]
    (first (filter #(= "url" (xml1-> % :name text)) prefs))))

(defn get-sfn-details [filename]
  (let [xml (parse-as-zip filename)
        fname (text (xml1-> xml :portlet-definition :fname))
        portlet-name (text (xml1-> xml :portlet-definition :name))
        groups (xml-> xml :portlet-definition :group text)
        url-pref (get-url-pref-el xml)
        url (xml1-> url-pref :value text)
        ]
    {:fname fname :pname portlet-name :groups groups :url url}))

(pprint (map get-sfn-details sfn-files))

; Found this by web search, so not going to tweak it to write directly to file
(defn ppxml [xml]
  (let [in (javax.xml.transform.stream.StreamSource.
             (java.io.StringReader. xml))
        writer (java.io.StringWriter.)
        out (javax.xml.transform.stream.StreamResult. writer)
        transformer (.newTransformer 
                      (javax.xml.transform.TransformerFactory/newInstance))]
    (.setOutputProperty transformer 
                        javax.xml.transform.OutputKeys/ENCODING "UTF-8")
    (.setOutputProperty transformer 
                        javax.xml.transform.OutputKeys/OMIT_XML_DECLARATION "yes")
    (.setOutputProperty transformer 
                        javax.xml.transform.OutputKeys/INDENT "yes")
    (.setOutputProperty transformer 
                        "{http://xml.apache.org/xslt}indent-amount" "2")
    (.setOutputProperty transformer 
                        javax.xml.transform.OutputKeys/METHOD "xml")
    (.transform transformer in out)
    (-> out .getWriter .toString)))

(defn el->xml
  "Convert a struct element into nicely formatted XML"
  [el]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
       (-> el
           emit
           with-out-str
           (clojure.string/replace #"&" "&amp;")
           (clojure.string/replace #"\n" "")
           ppxml)))

(defn gen-sfn-xml [{:keys [fname pname groups url]}]
  (let [role-els (mapv #(struct element :role {} [%]) groups)
        sfn-el
        (struct element :predefined-news {:script "classpath:/org/jasig/portlet/newsreader/io/import-PredefinedNewsDefinition_v1-0.crn.xml"}
                [(struct element :fname {} [(str "sfn-" fname)])
                 (struct element :className {} ["org.jasig.portlet.newsreader.adapter.RomeAdapter"])
                 (struct element :name {} [pname])
                 (struct element :parameters {} 
                         [(struct element :parameter {:name "url"} [url])])
                 (struct element :predefinedRoles {} role-els)])]
    (el->xml sfn-el)))

;(println (map (comp gen-sfn-xml get-sfn-details) sfn-files))

(defn save-sfn-datafile
  "Save sfn data to the 'out' directory (second commandline parameter)
  with a name like 'sfn-{fname}.pnd.xml'."
  [out-dir sfn-file]
  (let [sfn-details (get-sfn-details sfn-file)
        sfn-xml (gen-sfn-xml sfn-details)
        filename (str (:fname sfn-details) ".pnd.xml")
        sfn-file (file out-dir filename)]
    (spit sfn-file sfn-xml)))

(dorun (map (partial save-sfn-datafile news-dir) sfn-files))

;(System/exit 0)

;;
;; Functions for manipulating xml-zip
;;

(defn zip-walk 
  "function to walk zip from zip/next docs"
  [f z]
  (if (zip/end? z)
    (zip/root z)
    (recur f (zip/next (f z)))))

(defn print-tag
  "Debugging function to print tags when run through zip-walk"
  [loc]
  (let [node (zip/node loc)]
    (if (:tag node)
      (println (str (:tag node) " " (first (:content node))))
      ;(prn node)
      ))
  loc)

(defn remove-by-tag
  "Takes a tag keyword to remove nodes with that tag"
  [tag]
  (fn [loc]
    (let [is-tag? #(= tag (:tag (zip/node %)))]
      (if (is-tag? loc)
        (zip/remove loc)
        loc))))

(defn edit-by-tag
  "Takes a tag keyword and a function that takes an xml element and returns an xml element to edit the node"
  [tag f]
  (fn [loc]
    (let [is-tag? #(= tag (:tag (zip/node %)))]
      (if (is-tag? loc)
        (zip/edit loc f)
        loc))))

(defn rename-tag
  "Returns a function that renames a tag"
  [old-tag new-tag]
  (let [f #(assoc % :tag new-tag)]
    (edit-by-tag old-tag f)))

(defn replace-content-text
  "Change the text content of a tag based on a regex"
  [tag regex new-str]
  (let [f #(assoc % :content (vector (replace-first (-> % :content first) regex new-str)))]
    (edit-by-tag tag f)))

(defn replace-attribute-text
  "Change the text of a tag's attribute based on a regex"
  [tag attr regex new-str]
  (let [f #(assoc-in % [:attrs attr] (replace-first (get-in % [:attrs attr]) regex new-str))]
    (edit-by-tag tag f)))

(defn remap-content-text
  "Map the content text to new value based on a map"
  ([tag content-map]
   (let [f #(assoc % :content (vector (get content-map (-> % :content first) (-> % :content first))))]
     (edit-by-tag tag f)))
  ([tag content-map default]
   (let [f #(assoc % :content (vector (get content-map (-> % :content first) default)))]
     (edit-by-tag tag f))))

(defn add-attr-map
  "Add an map of attributes to a tag"
  [tag attrs]
  (edit-by-tag tag #(assoc % :attrs attrs)))

;;
;; Convert from old format to new format
;;

;(println (zip/node (parse-as-zip (first sfn-files))))
;(zip-walk print-tag (parse-as-zip (first sfn-files)))

; note: comp functions are applied in reverse order
(defn convert-file [file]
  (let [xml (parse-as-zip file)
        fname (xml1-> xml :portlet-definition :fname text)
        sfn-name (str "sfn-" fname)]
    (-> xml
        (xml1-> :portlet-descriptor :ns2:portletName)
        (zip/edit #(assoc % :content (vector "news")))
        zip/up
        zip/up
        get-url-pref-el
        (xml1-> :name)
        (zip/edit #(assoc % :content (vector "Whitelist.regexValues")))
        zip/up
        (xml1-> :value)
        (zip/edit #(assoc % :content (vector sfn-name)))
        print-tag
        zip/root)))

;(println (first sfn-files))
;(emit (convert-file (first sfn-files)))
;(System/exit 0)

(defn print-converted-file [file]
  (println file)
  (-> file
      convert-file
      println))
;(dorun (map print-converted-file sfn-files))

(defn write-group-to-file
  [file]
  (let []
    (try
      (let [xml-str (-> file
                        convert-file
                        ;zip/node
                        el->xml)]
        (spit file xml-str))
      (catch Exception e (println "caught exception for" file ":" (.getMessage e))))))


(dorun (map write-group-to-file sfn-files))
