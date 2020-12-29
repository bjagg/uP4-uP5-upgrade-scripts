#!/usr/bin/env lein-exec

;;
;; For a given directory containing groups data files,
;; update from uP4 to uP5 in place
;;

(use '[leiningen.exec :only (deps)])
(deps '[[org.clojure/data.xml "0.0.8"]
        [org.clojure/data.zip "1.0.0"]])

(require '(clojure [zip :as zip :only (xml-zip children end? root next branch? edit remove node insert-left)]
                   [string :refer (replace-first ends-with?)]
                   [pprint :refer (pprint)]
                   [xml :refer (emit parse element emit-element)])
         '(clojure.java [io :refer (as-file file input-stream)])
         ;'(clojure.data [xml :as xml]) -- parse and emit from this package should be better than clojure.xml, but they aren't
         '(clojure.data.zip [xml :refer (text xml-> xml1->)]))

(import '[javax.xml.parsers SAXParserFactory])

;;
;; Read the file passed as the first argument
;; from former versions of uPortal (4 and earlier)
;;

(def data-dir (as-file (nth *command-line-args* 1)))

(if (and (.exists data-dir) (.isDirectory data-dir))
  (println "Directory" data-dir "found")
  (do
    (println "Directory" data-dir "could not be found! - exting ...")
    (System/exit 1)))

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

;(println (zip/node (parse-as-zip (first data-files))))
;(zip-walk print-tag (parse-as-zip (first data-files)))

(defn create-browse-perms
  [groups]
  (let [group-els (into [] (map #(struct element :group {} [%]) groups))]
    ;(println group-els)
    ;(emit-element (first group-els))
    (struct element :permissions {} 
            [(struct element :permission {:system "UP_PORTLET_SUBSCRIBE" :activity "BROWSE"} group-els)])))

; note: comp functions are applied in reverse order
(defn convert-file [file]
  (let [xml (-> file
                (parse non-validating))
        groups (-> xml
                   zip/xml-zip
                   (xml-> :portlet-definition :group text))
        perms (create-browse-perms groups)]
    ;(println groups)
    ;(emit-element perms)
    (-> xml
        zip/xml-zip
        (xml1-> :parameter)
        (zip/insert-left perms)
        zip/root)))

;(emit (convert-file (first data-files)))
;(System/exit 0)

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

(defn print-converted-file [file]
  (println file)
  (-> file
      convert-file
      println))
;(dorun (map print-converted-file data-files))

(defn write-group-to-file
  [file]
  (let []
    (try
      (let [xml-str (-> file
                        convert-file
                        ;zip/node
                        emit
                        with-out-str
                        (clojure.string/replace #"&" "&amp;")
                        (clojure.string/replace #"\n" "")
                        ppxml)]
        (spit file (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" xml-str)))
      (catch Exception e (println "caught exception for" file ":" (.getMessage e))))))


(dorun (map write-group-to-file data-files))
