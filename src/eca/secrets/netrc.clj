(ns eca.secrets.netrc
  (:require
   [clojure.string :as string]))

(set! *warn-on-reflection* true)

(defn- parse-entry
  "Parses a single netrc entry from multiple lines.
   Returns a credential map or nil if entry is invalid."
  [lines]
  (let [tokens (mapcat #(string/split % #"\s+") lines)
        tokens (remove string/blank? tokens)
        pairs (partition 2 2 nil tokens)
        entry (into {} (map vec pairs))]
    (when (and (get entry "machine")
               (get entry "login")
               (get entry "password"))
      {:machine (get entry "machine")
       :login (get entry "login")
       :password (get entry "password")
       :port (get entry "port")})))

(defn parse
  "Parses netrc multi-line format into credential maps.
   Returns a vector of credential maps.
   Entries without login field are skipped.
   Empty files return empty vector []."
  [content]
  (if (string/blank? content)
    []
    (let [lines (string/split-lines content)
          ;; Remove comments and blank lines
          lines (remove #(or (string/blank? %)
                             (string/starts-with? (string/trim %) "#"))
                        lines)
          ;; Group lines by machine keyword
          entries (loop [remaining lines
                         current-entry []
                         result []]
                    (if-let [line (first remaining)]
                      (let [trimmed (string/trim line)]
                        (if (string/starts-with? trimmed "machine")
                          ;; Start of new entry
                          (if (seq current-entry)
                            (recur (rest remaining)
                                   [line]
                                   (conj result current-entry))
                            (recur (rest remaining)
                                   [line]
                                   result))
                          ;; Continuation of current entry
                          (recur (rest remaining)
                                 (conj current-entry line)
                                 result)))
                      ;; End of input
                      (if (seq current-entry)
                        (conj result current-entry)
                        result)))]
      ;; Parse each entry
      (->> entries
           (keep parse-entry)
           vec))))

(defn format-entry
  "Formats a single credential map into netrc format."
  [{:keys [machine login password port]}]
  (str "machine " machine "\n"
       "login " login "\n"
       "password " password "\n"
       (when port (str "port " port "\n"))))

(defn format-entries
  "Formats multiple credential maps into complete netrc file content."
  [entries]
  (string/join "\n" (map format-entry entries)))

(defn read-file
  "Reads and parses all credential entries from a netrc file."
  [filename]
  (-> filename
      slurp
      parse))

(defn write-file
  "Formats and writes credential entries to a netrc file."
  [filename entries]
  (let [content (format-entries entries)]
    (spit filename content)))
