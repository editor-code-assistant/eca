(ns eca.features.tools.shell-parser
  "Quote-aware tokenizer that breaks shell command strings into the individual
   commands they run, used for granular tool call approval and display.

   Fails closed: any construct whose effect cannot be statically reasoned
   about (command substitution, subshells, heredocs, process substitution)
   makes tokenization return nil, and commands that execute other commands
   (wrappers like sudo/xargs) or write via redirections yield no approval key,
   so the tool call falls back to asking the user."
  (:require
   [clojure.string :as string]))

(set! *warn-on-reflection* true)

(def ^:private assignment-regex #"[A-Za-z_][A-Za-z0-9_]*=.*")

(def ^:private plain-word-regex #"[A-Za-z0-9_@%+:,./~^-]+")

(def ^:private wrapper-commands
  "Commands that execute other commands or arbitrary code: remembering them
   would whitelist arbitrary execution, so they never produce approval keys."
  #{"sudo" "doas" "su" "sh" "bash" "zsh" "dash" "ksh" "csh" "tcsh" "fish" "pwsh"
    "eval" "exec" "source" "." "xargs" "env" "nohup" "nice" "setsid" "stdbuf"
    "timeout" "command" "builtin" "time" "script" "chroot" "watch"})

(def ^:private subcommand-aware-commands
  "Commands whose first non-flag argument narrows what they do, so approval
   keys include it (e.g. `git checkout`, `npm install`)."
  #{"git" "gh" "npm" "npx" "pnpm" "yarn" "bun" "deno" "cargo" "rustup" "go"
    "docker" "podman" "kubectl" "helm" "terraform" "aws" "gcloud" "az"
    "brew" "apt" "apt-get" "dnf" "pacman" "nix" "systemctl" "make" "just"
    "clojure" "clj" "lein" "bb" "mvn" "gradle" "pip" "pip3" "uv" "uvx" "poetry"
    "bundle" "gem" "rake" "rails" "mix"})

(defn ^:private append-ch [st c]
  (update st :token #(str (or % "") c)))

(defn ^:private digits-run-end
  "Returns the index after a run of digits starting at i, or nil if none."
  [^String s ^long i]
  (let [n (.length s)]
    (loop [j i]
      (if (and (< j n) (Character/isDigit (.charAt s j)))
        (recur (inc j))
        (when (> j i) j)))))

(defn ^:private finish-token
  "Completes the current token: redirect targets are consumed (non-/dev/null
   output targets flag the unit), regular tokens are appended."
  [{:keys [token pending-redirect] :as st}]
  (cond
    (nil? token)
    st

    (= :out pending-redirect)
    (-> st
        (assoc :token nil :pending-redirect nil)
        (cond-> (not= "/dev/null" token) (assoc :redirect-out? true)))

    (= :in pending-redirect)
    (assoc st :token nil :pending-redirect nil)

    :else
    (-> st
        (update :tokens conj token)
        (assoc :token nil))))

(defn ^:private finish-unit
  [st]
  (let [{:keys [tokens redirect-out? pending-redirect] :as st} (finish-token st)]
    (if pending-redirect ;; dangling redirection like `echo > && ls`
      (assoc st :error true)
      (-> st
          (cond-> (seq tokens)
            (update :units conj {:tokens tokens
                                 :redirect-out? (boolean redirect-out?)}))
          (assoc :tokens [] :redirect-out? false)))))

(defn ^:private tokenize
  "Splits a shell command string into units (one per command) of word tokens,
   respecting quotes and escapes. Returns a vector of
   {:tokens [string] :redirect-out? boolean} or nil when the command contains
   constructs we refuse to reason about (command/process substitution,
   subshells, heredocs, unbalanced quotes)."
  [^String s]
  (let [n (.length s)]
    (loop [i (long 0)
           st {:mode :normal :token nil :tokens [] :units []
               :redirect-out? false :pending-redirect nil}]
      (cond
        (:error st)
        nil

        (>= i n)
        (let [st (finish-token st)]
          (when (and (= :normal (:mode st))
                     (not (:pending-redirect st)))
            (:units (finish-unit st))))

        :else
        (let [c (.charAt s i)]
          (case (:mode st)
            :single
            (if (= \' c)
              (recur (inc i) (assoc st :mode :normal))
              (recur (inc i) (append-ch st c)))

            :double
            (cond
              (= \" c)
              (recur (inc i) (assoc st :mode :normal))

              (= \` c)
              nil

              (and (= \$ c) (< (inc i) n) (= \( (.charAt s (inc i))))
              nil

              (and (= \\ c) (< (inc i) n))
              (recur (+ i 2) (append-ch st (.charAt s (inc i))))

              :else
              (recur (inc i) (append-ch st c)))

            :normal
            (cond
              (= \\ c)
              (when (< (inc i) n)
                (let [nc (.charAt s (inc i))]
                  (if (= \newline nc)
                    (recur (+ i 2) st)
                    (recur (+ i 2) (append-ch st nc)))))

              (= \' c)
              (recur (inc i) (-> st (update :token #(or % "")) (assoc :mode :single)))

              (= \" c)
              (recur (inc i) (-> st (update :token #(or % "")) (assoc :mode :double)))

              ;; command substitution, subshells and process substitution
              (or (= \` c) (= \( c) (= \) c))
              nil

              (and (= \# c) (nil? (:token st)))
              (recur (long (or (string/index-of s "\n" i) n)) st)

              (or (= \space c) (= \tab c))
              (recur (inc i) (finish-token st))

              (or (= \newline c) (= \; c))
              (recur (inc i) (finish-unit st))

              (= \& c)
              (cond
                (and (< (inc i) n) (= \& (.charAt s (inc i))))
                (recur (+ i 2) (finish-unit st))

                ;; &> and &>> redirect both stdout and stderr
                (and (< (inc i) n) (= \> (.charAt s (inc i))))
                (recur (long (if (and (< (+ i 2) n) (= \> (.charAt s (+ i 2)))) (+ i 3) (+ i 2)))
                       (-> st finish-token (assoc :pending-redirect :out)))

                :else
                (recur (inc i) (finish-unit st)))

              (= \| c)
              (if (and (< (inc i) n) (contains? #{\| \&} (.charAt s (inc i))))
                (recur (+ i 2) (finish-unit st))
                (recur (inc i) (finish-unit st)))

              (= \> c)
              (let [st (if (and (:token st) (re-matches #"\d+" (:token st)))
                         (assoc st :token nil) ;; fd number prefix like the 2 in 2>
                         (finish-token st))]
                (cond
                  ;; >> append
                  (and (< (inc i) n) (= \> (.charAt s (inc i))))
                  (recur (+ i 2) (assoc st :pending-redirect :out))

                  ;; >&N fd dup (2>&1) is harmless, >&file redirects
                  (and (< (inc i) n) (= \& (.charAt s (inc i))))
                  (if-let [j (or (digits-run-end s (+ i 2))
                                 (when (and (< (+ i 2) n) (= \- (.charAt s (+ i 2))))
                                   (+ i 3)))]
                    (recur (long j) st)
                    (recur (+ i 2) (assoc st :pending-redirect :out)))

                  ;; >| clobber
                  (and (< (inc i) n) (= \| (.charAt s (inc i))))
                  (recur (+ i 2) (assoc st :pending-redirect :out))

                  :else
                  (recur (inc i) (assoc st :pending-redirect :out))))

              (= \< c)
              (let [st (if (and (:token st) (re-matches #"\d+" (:token st)))
                         (assoc st :token nil)
                         (finish-token st))]
                (cond
                  ;; <<< herestring: the next word is stdin data
                  (and (< (+ i 2) n) (= \< (.charAt s (inc i))) (= \< (.charAt s (+ i 2))))
                  (recur (+ i 3) (assoc st :pending-redirect :in))

                  ;; << heredoc: refuse, content may expand substitutions
                  (and (< (inc i) n) (= \< (.charAt s (inc i))))
                  nil

                  ;; <&N fd dup
                  (and (< (inc i) n) (= \& (.charAt s (inc i))))
                  (if-let [j (or (digits-run-end s (+ i 2))
                                 (when (and (< (+ i 2) n) (= \- (.charAt s (+ i 2))))
                                   (+ i 3)))]
                    (recur (long j) st)
                    (recur (+ i 2) (assoc st :pending-redirect :in)))

                  :else
                  (recur (inc i) (assoc st :pending-redirect :in))))

              :else
              (recur (inc i) (append-ch st c)))))))))

(defn ^:private unit->command+args
  "Strips leading VAR=value assignment tokens, returning [command args]."
  [{:keys [tokens]}]
  (let [[command & args] (drop-while #(re-matches assignment-regex %) tokens)]
    [command (vec args)]))

(defn ^:private plain-word? [s]
  (boolean (and s (re-matches plain-word-regex s))))

(defn ^:private unit->approval-key
  [{:keys [redirect-out?] :as unit}]
  (let [[command args] (unit->command+args unit)]
    (when (and command
               (not redirect-out?)
               (plain-word? command)
               (not (contains? wrapper-commands command)))
      (if (contains? subcommand-aware-commands command)
        (when-let [subcommand (first (remove #(string/starts-with? % "-") args))]
          (when (plain-word? subcommand)
            (str command " " subcommand)))
        command))))

(defn parse
  "Parses a shell command string into a breakdown of the individual commands
   it runs, splitting chained commands (&&, ||, ;, |) into separate entries.
   Each entry includes :approvalKey (protocol camelCase), the key an approve &
   remember would save for that command, absent when the command can never be
   auto-approved.
   Returns {:commands [{:command \"ls\" :args [\"-la\"] :approvalKey \"ls\"} ...]}
   or nil when the command cannot be safely tokenized."
  [command]
  (when (and (string? command) (not (string/blank? command)))
    (when-let [units (tokenize command)]
      (let [commands (into []
                           (keep (fn [unit]
                                   (let [[cmd args] (unit->command+args unit)]
                                     (when cmd
                                       (let [approval-key (unit->approval-key unit)]
                                         (cond-> {:command cmd :args args}
                                           approval-key (assoc :approvalKey approval-key)))))))
                           units)]
        (when (seq commands)
          {:commands commands})))))

(defn approval-keys
  "Derives the approval keys for a shell command string, one key per command:
   command + subcommand for subcommand-aware commands (\"git checkout\"),
   the plain command otherwise (\"rg\").
   Returns {:keys #{string} :complete? boolean} or nil when the command cannot
   be tokenized at all. :complete? false means at least one command yielded no
   key (wrapper command, output redirection, dynamic command word), so the
   whole call must still be asked."
  [command]
  (when (and (string? command) (not (string/blank? command)))
    (when-let [units (not-empty (tokenize command))]
      (let [keys' (mapv unit->approval-key units)]
        {:keys (into #{} (remove nil?) keys')
         :complete? (every? some? keys')}))))
