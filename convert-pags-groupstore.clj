#!/usr/bin/env lein-exec

(use '[leiningen.exec :only (deps)])
(deps '[[org.clojure/data.xml "0.0.8"]
        [org.clojure/data.zip "1.0.0"]])

(require '(clojure [zip :as zip :refer (xml-zip children end? root next branch? edit remove node)]
                   [string :as s :refer (split split-lines trim replace-first)]
                   [pprint :as pp :refer (pprint print-table)])
         '(clojure.java [io :as io :refer (as-file file input-stream)])
         '(clojure.data [zip :as dz]
                        [xml :as xml])
         '(clojure.data.zip [xml :as dzx]))

(import '[javax.xml.parsers SAXParserFactory])

;;
;; Read the file passed as the first argument -- should be the PAGS groupstore XML file
;; from former versions of uPortal (4 and earlier)
;;

(def groupstore-filename (nth *command-line-args* 1))
(def groupstore-file (io/as-file groupstore-filename))

(if (.exists groupstore-file)
  (println "File" groupstore-filename "found")
  (do
    (println "File" groupstore-filename "could not be found! - exting ...")
    (System/exit 1)))

;;
;; Determine output directory
;;

(defn get-valid-output-dir
  []
  (if-let [file (io/as-file (nth *command-line-args* 2 nil))]
    (if (or (.isDirectory file) (.mkdirs file))
      (.getAbsolutePath file)
      ".")
    "."))

(def output-dir (get-valid-output-dir))

(println output-dir)

;;
;; Parse file into a xml zipper
;;

(def groupstore-xml (xml/parse (io/input-stream groupstore-file)))
(def groupstore-zip (zip/xml-zip groupstore-xml))

;;
;; Create map of old group keys to group names
;;

(defn map-group-key-name
  "Returns a map from the group key to the name"
  []
  (->> (dzx/xml-> groupstore-zip :group)
       (map #(hash-map (dzx/xml1-> % :group-key dzx/text) (dzx/xml1-> % :group-name dzx/text)))
       (into {})))

(def key-name-remap (map-group-key-name))
;(pp/pprint key-name-remap)

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

(defn remap-content-text
  "Map the content text to new value based on a map"
  [tag content-map]
  (let [f #(assoc % :content (vector (get content-map (-> % :content first) "no key found")))]
    (edit-by-tag tag f)))

(defn add-attr-map
  "Add an map of attributes to a tag"
  [tag attrs]
  (edit-by-tag tag #(assoc % :attrs attrs)))

;;
;; Convert from old format to new format
;;

;(println (zip/node (first (dzx/xml-> groupstore-zip :group))))
;(zip-walk print-tag groupstore-zip)

; note: comp functions are applied in reverse order
(def convert-pags (comp (remove-by-tag :group-key)
                        (rename-tag :group :pags-group)
                        (rename-tag :group-description :description)
                        (rename-tag :group-name :name)
                        (rename-tag :member-key :member-name)
                        (remap-content-text :member-key key-name-remap)
                        (replace-content-text :tester-class #"jasig" "apereo")
                        (add-attr-map :group {:script "classpath://org/jasig/portal/io/import-pags-group_v4-1.crn"})))
(def new-xml (zip-walk convert-pags groupstore-zip))
;(prn new-xml)
;(zip-walk print-tag (zip/xml-zip new-xml))

;; Create a sequence of the groups as elements

(def group-locs (dzx/xml-> (zip/xml-zip new-xml) :pags-group))
;(def group-locs (->> groupstore-xml
;                    :content
;                    (filter #(= (:tag %) :group))))

(println)
;(println (count group-locs))
;(println (count (dzx/xml-> groupstore-zip :group)))
;(println)
(println (zip/node (first group-locs)))


;; Print each <pags-group> in a separate file based on <name>

(defn get-group-name
  [group-loc]
  (dzx/xml1-> group-loc :name dzx/text))

(defn calc-group-filename
  [group-loc]
  (-> group-loc
      get-group-name
      (clojure.string/replace #" " "_")
      (str ".pags-group.xml")))

(println "3rd pags name:" (get-group-name (nth group-locs 2)))
(println "3rd pags filename:" (calc-group-filename (nth group-locs 2)))
(System/exit 0)

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

(defn write-group-to-file
  [group-loc]
  (let [filename (file output-dir (calc-group-filename group-loc))]
    (try
      (println filename)
      (let [xml-str (-> group-loc
                        zip/node
                        xml/emit
                        with-out-str
                        (clojure.string/replace #"&" "&amp;")
                        (clojure.string/replace #"\n" "")
                        ppxml)]
        (spit filename (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" xml-str)))
      (catch Exception e (println "caught exception for" filename ":" (.getMessage e))))))


;(-> group-locs
;    (nth 2)
;    write-group-to-file)

(dorun (map write-group-to-file group-locs))
