(ns eca.secrets.authinfo
  (:require
   [clojure.string :as string]))

(set! *warn-on-reflection* true)

(defn- parse-line
  "Parses a single authinfo line into a credential map.
   Returns a credential map or nil if line is invalid."
  [line]
  (let [trimmed (string/trim line)]
    (when-not (or (string/blank? trimmed)
                  (string/starts-with? trimmed "#"))
      (let [tokens (string/split trimmed #"\s+")
            tokens (remove string/blank? tokens)
            pairs (partition 2 2 nil tokens)
            entry (into {} (map vec pairs))]
        (when (and (get entry "machine")
                   (get entry "login")
                   (get entry "password"))
          {:machine (get entry "machine")
           :login (get entry "login")
           :password (get entry "password")
           :port (get entry "port")})))))

(defn parse
  "Parses authinfo single-line format into credential maps.
   Returns a vector of credential maps.
   Entries without login field are skipped.
   Empty files return empty vector []."
  [content]
  (if (string/blank? content)
    []
    (let [lines (string/split-lines content)]
      (->> lines
           (keep parse-line)
           vec))))

(defn format-entry
  "Formats a single credential map into authinfo format."
  [{:keys [machine login password port]}]
  (str "machine " machine " "
       "login " login " "
       "password " password
       (when port (str " port " port))))

(defn format-entries
  "Formats multiple credential maps into complete authinfo file content."
  [entries]
  (string/join "\n" (map format-entry entries)))

(defn read-file
  "Reads and parses all credential entries from an authinfo file."
  [filename]
  (-> filename
      slurp
      parse))

(defn write-file
  "Formats and writes credential entries to an authinfo file."
  [filename entries]
  (let [content (format-entries entries)]
    (spit filename content)))
