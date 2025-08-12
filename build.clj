(ns build
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def basis {:project "deps.edn"})
(def file "target/eca.jar")

(def ^:private aarch64?
  (-> (System/getProperty "os.arch")
      (string/lower-case)
      (string/includes? "aarch64")))

(def ^:private linux?
  (-> (System/getProperty "os.name")
      (string/lower-case)
      (string/includes? "linux")))

(defn clean [_]
  (b/delete {:path "target"}))

(defn ^:private aot-jar [opts]
  (clean opts)
  (println "Building uberjar...")
  (let [basis (b/create-basis (update basis :aliases concat (:extra-aliases opts)))
        src-dirs (into ["src" "resources"] (:extra-dirs opts))]
    (b/copy-dir {:src-dirs src-dirs
                 :target-dir class-dir})
    (b/compile-clj {:basis basis
                    :src-dirs src-dirs
                    :java-opts ["-server"]
                    :class-dir class-dir})
    (b/uber {:class-dir class-dir
             :uber-file file
             :main 'eca.main
             :basis basis})))

(defn ^:private bin
  "Create an executable `eca` script out of UBER-FILE jar with
  the given OPTS.

  OPTS can be a map of
  :jvm-opts A vector of options ot pass to the JVM."
  [opts]
  (when (.exists (io/file "eca"))
    (io/delete-file "eca"))
  (println "Generating bin...")
  (let [jvm-opts (concat (:jvm-opts opts []) ["-server"])]
    ((requiring-resolve 'deps-bin.impl.bin/build-bin)
     {:jar file
      :name "eca"
      :jvm-opts jvm-opts
      :skip-realign true})))

(defn debug-cli [opts]
  (aot-jar (merge opts {:extra-aliases [:dev :test]
                        :extra-dirs ["dev"]}))
  (bin {:jvm-opts ["-XX:-OmitStackTraceInFastThrow"
                   "-Djdk.attach.allowAttachSelf=true"
                   "-Dclojure.core.async.go-checking=true"]}))

(defn prod-jar [opts]
  (aot-jar (merge opts {:extra-aliases [:native]})))

(defn prod-cli [opts]
  (aot-jar opts)
  (bin {}))

(defn native-cli [opts]
  (println "Building native image...")
  (if-let [graal-home (System/getenv "GRAALVM_HOME")]
    (let [jar (or (System/getenv "ECA_JAR")
                  (do (prod-jar opts)
                      file))
          native-image (if (fs/windows?) "native-image.cmd" "native-image")
          command (->> [(str (io/file graal-home "bin" native-image))
                        "-jar" jar
                        "eca"
                        "-H:+ReportExceptionStackTraces"
                        "--verbose"
                        "--no-fallback"
                        "--native-image-info"
                        "--features=clj_easy.graal_build_time.InitClojureClasses"
                        (when-not (fs/windows?) "-march=compatibility")
                        "-O1"
                        (or (System/getenv "ECA_XMX")
                            "-J-Xmx8g")
                        (when (and linux? aarch64?)
                          ["-Djdk.lang.Process.launchMechanism=vfork"
                           "-H:PageSize=65536"])
                        (when (= "true" (System/getenv "ECA_STATIC"))
                          ["--static"
                           (if (= "true" (System/getenv "ECA_MUSL"))
                             ["--libc=musl" "-H:CCompilerOption=-Wl,-z,stack-size=2097152"]
                             ["-H:+StaticExecutableWithDynamicLibC"])])
                        (:extra-args opts)]
                       (flatten)
                       (remove nil?))
          {:keys [exit]} (b/process {:command-args command})]
      (when-not (= 0 exit)
        (System/exit exit)))
    (println "Set GRAALVM_HOME env")))
