(ns make
  (:require
   [babashka.deps :as deps]
   [babashka.fs :as fs]
   [babashka.process :as p]
   [babashka.tasks :refer [shell]]
   [clojure.string :as string]
   [entrypoint]))

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

(defn ^:private make-literal [a]
  (.replace a "\"" "\\\""))

(defn ^:private extract-text-between [prefix suffix from-string]
  (let [pattern (str (make-literal prefix) "([\\s\\S]*?)" (make-literal suffix))]
    (second (re-find (re-pattern pattern) from-string))))

(defn debug-graal [& [args]]
  (shell (format "%s/bin/java -agentlib:native-image-agent=config-output-dir=%s -jar target/eca.jar %s"
                 (System/getenv "GRAALVM_HOME")
                 (System/getenv "PWD")
                 args)))

(defn debug-cli
  "Build the `eca[.bat]` debug executable (suppots `cider-nrepl`)."
  []
  (build "debug-cli")
  (mv-here (fs/path (eca-bin-filename :script))))

(defn prod-jar []
  (build "prod-jar")
  (mv-here "target/eca.jar"))

(defn prod-cli []
  (build "prod-cli")
  (mv-here (fs/path (eca-bin-filename :script))))

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

(defn get-last-changelog-entry [version]
  (println (->> (slurp "CHANGELOG.md")
                (extract-text-between (str "## " version) "## ")
                string/trim)))

(defn unit-test []
  (println :running-unit-tests...)
  (clj! ["-M:test"])
  (println))

(defn integration-test
  "Run the integration tests in 'test/integration-test/' using `./eca[.bat|.exe]`.

  There should only be one eca executable found, throws error
  otherwise."
  []
  (let [eca-bins (->> [:native :script] (map eca-bin-filename) distinct)
        eca-bins-found (->> eca-bins (filter fs/exists?) (into #{}))]

    (case (count eca-bins-found)
      0 (throw (ex-info "No eca executables found." {:searched-for eca-bins}))
      1 (entrypoint/run-all (str (first eca-bins-found)))
      (throw (ex-info "More than one eca executables found. Can only work with one."
                      {:bin-found eca-bins-found})))))
