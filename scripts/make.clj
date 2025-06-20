(ns make
  (:require
   [babashka.deps :as deps]
   [babashka.fs :as fs]
   [babashka.process :as p]
   [babashka.tasks :refer [shell]]
   [clojure.java.io :as io]
   [clojure.string :as string]))

(def windows? (#'fs/windows?))

(defn ^:private replace-in-file [file regex content]
  (as-> (slurp file) $
    (string/replace $ regex content)
    (spit file $)))

(defn ^:private add-changelog-entry [tag comment]
  (replace-in-file "CHANGELOG.md"
                   #"## Unreleased"
                   (if comment
                     (format "## Unreleased\n\n## %s\n\n- %s" tag comment)
                     (format "## Unreleased\n\n## %s" tag))))

(defn ^:private mv-here [file]
  (fs/move file "." {:replace-existing true}))

(defn ^:private clj! [cmd]
  (-> (deps/clojure cmd {:inherit true})
      (p/check)))

(defn ^:private build [tool] (clj! ["-T:build" tool]))

(defn eca-bin-filename
  [usage]
  (cond-> "eca"
    windows? (str (case usage
                    :native ".exe"
                    :script ".bat"))))

(defn debug-cli
  "Build the `eca[.bat]` debug executable (suppots `cider-nrepl`)."
  []
  (build "debug-cli")
  (mv-here (fs/path (eca-bin-filename :script))))

(defn prod-jar []
  (build "prod-jar")
  (mv-here "target/eca.jar"))

(defn native-cli
  "Build the native `eca[.exe]` cli executable with `graalvm`."
  []
  (build "native-cli")
  (mv-here (fs/path (eca-bin-filename :native))))

(defn tag [& [tag]]
  (shell "git fetch origin")
  (shell "git pull origin HEAD")
  (spit "resources/ECA_VERSION" tag)
  (add-changelog-entry tag nil)
  (prod-jar)
  (shell "git add resources/ECA_VERSION CHANGELOG.md")
  (shell (format "git commit -m \"Release: %s\"" tag))
  (shell (str "git tag " tag))
  (shell "git push origin HEAD")
  (shell "git push origin --tags"))

(defn run-file
  "Starts the server process and send the content of given path as stdin"
  [& [path]]
  (-> (p/process {:cmd ["clojure" "-M:dev" "-m" "eca.main" "server"]
                  :in (slurp path)
                  :out :string
                  :err :string})
      p/check
      :err
      println))
