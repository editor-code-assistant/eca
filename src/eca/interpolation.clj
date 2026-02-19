(ns eca.interpolation
  "Expands dynamic placeholder patterns in strings:
  - `${env:SOME-ENV:default-value}`: environment variable with optional default
  - `${file:/some/path}`: file content (relative paths resolved from cwd)
  - `${classpath:path/to/file}`: classpath resource content
  - `${netrc:api.provider.com}`: credential from Unix netrc files"
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.logger :as logger]
   [eca.secrets :as secrets]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[INTERPOLATION]")

(defn get-env [env] (System/getenv env))

(defn replace-dynamic-strings
  "Given a string and a current working directory, look for patterns replacing its content:
  - `${env:SOME-ENV:default-value}`: Replace with a env falling back to a optional default value
  - `${file:/some/path}`: Replace with a file content checking from cwd if relative
  - `${classpath:path/to/file}`: Replace with a file content found checking classpath
  - `${netrc:api.provider.com}`: Replace with the content from Unix net RC [credential files](https://eca.dev/config/models/#credential-file-authentication)"
  [s cwd config]
  (some-> s
          (string/replace #"\$\{env:([^:}]+)(?::([^}]*))?\}"
                          (fn [[_match env-var default-value]]
                            (or (get-env env-var) default-value "")))
          (string/replace #"\$\{file:([^}]+)\}"
                          (fn [[_match file-path]]
                            (try
                              (let [file-path (fs/expand-home file-path)]
                                (slurp (str (if (fs/absolute? file-path)
                                              file-path
                                              (if cwd
                                                (fs/path cwd file-path)
                                                (fs/path file-path))))))
                              (catch Exception _
                                (logger/warn logger-tag "File not found when parsing string:" s)
                                ""))))
          (string/replace #"\$\{classpath:([^}]+)\}"
                          (fn [[_match resource-path]]
                            (try
                              (slurp (io/resource resource-path))
                              (catch Exception e
                                (logger/warn logger-tag "Error reading classpath resource:" (.getMessage e))
                                ""))))
          (string/replace #"\$\{netrc:([^}]+)\}"
                          (fn [[_match key-rc]]
                            (try
                              (or (secrets/get-credential key-rc (get config "netrcFile")) "")
                              (catch Exception e
                                (logger/warn logger-tag "Error reading netrc credential:" (.getMessage e))
                                ""))))))
