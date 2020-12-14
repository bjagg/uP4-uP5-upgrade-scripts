#!/usr/bin/env lein-exec

(use '[clojure.java.io :only (as-file)]
     '[clojure.xml :as xml]
     '[clojure.zip :as zip]
     '[clojure.string :as s :only (split split-lines trim)]
     '[clojure.pprint :as pp :only (pprint print-table)])
(import '[javax.xml.parsers SAXParserFactory])

;; Read the file passed as the first argument -- should be the PAGS groupstore XML file
;; from former versions of uPortal (4 and earlier)

(def groupstore-filename (nth *command-line-args* 1))
(def groupstore-file (as-file groupstore-filename))

(if (.exists groupstore-file)
  (println "File" groupstore-filename "found")
  (do
    (println "File" groupstore-filename "could not be found! - exting ...")
    (System/exit 1)))

;; Parse file into a sequence of PAGS

(defn non-validating [s ch]
  (..
    (doto
      (SAXParserFactory/newInstance)
      (.setFeature 
        "http://apache.org/xml/features/nonvalidating/load-external-dtd" false))
    (newSAXParser)
    (parse s ch)))

(def groupstore-xml (xml/parse groupstore-file non-validating))
;(def groupstore-zip (zip/xml-zip groupstore-xml))
;(def group-seq (zip/children groupstore-zip))
(def group-seq (->> groupstore-xml
                    :content
                    (filter #(= (:tag %) :pags-group))
                    ))

(println)
(println (count group-seq))
;(println)
;(prn (first group-seq))
(println)

;; Convert from old format to new format

; this was done by hand for this exercise, but should be added here


;; Print each <pags-group> in a separate file based on <name>

(defn get-group-name
  [group]
  (->> group
       :content
       (filter #(= (:tag %) :name))
       first
       :content
       first))

(defn calc-group-filename
  [group]
  (-> group
      get-group-name
      (clojure.string/replace #" " "_")
      (str ".pags-group.xml")))

;(println "3rd pags name:" (get-group-name (nth group-seq 2)))
;(println "3rd pags filename:" (calc-group-filename (nth group-seq 2)))

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
  [group]
  (let [filename (calc-group-filename group)]
    (try
      (let [xml-str (-> group 
                        emit
                        with-out-str
                        (clojure.string/replace #"&" "&amp;")
                        (clojure.string/replace #"\n" "")
                        ppxml)]
        (spit filename (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" xml-str)))
      (catch Exception e (println "caught exception for" filename ":" (.getMessage e))))))


;(-> group-seq
;    (nth 2)
;    write-group-to-file)

(dorun (map write-group-to-file group-seq))
