(ns jepsen.os.debian
  "Common tasks for Debian boxes."
  (:use clojure.tools.logging)
  (:require [clojure.set :as set]
            [jepsen.util :refer [meh]]
            [jepsen.os :as os]
            [jepsen.control :as c]
            [jepsen.control.net :as net]
            [clojure.string :as str]))

(defn setup-hostfile!
  "Makes sure the hostfile has a loopback entry for the local hostname"
  []
  (let [name    (c/exec :hostname)
        hosts   (c/exec :cat "/etc/hosts")
        hosts'  (->> hosts
                     str/split-lines
                     (map (fn [line]
                            (if (re-find #"^127\.0\.0\.1\tlocalhost$" line)
                              (str "127.0.0.1\tlocalhost " name)
                              line)))
                     (str/join "\n"))]
    (when-not (= hosts hosts')
      (c/su (c/exec :echo hosts' :> "/etc/hosts")))))


(defn installed
  "Given a list of debian packages (strings, symbols, keywords, etc), returns
  the set of packages which are installed, as strings."
  [pkgs]
  (let [pkgs (->> pkgs (map name) set)]
    (->> (apply c/exec :dpkg :--get-selections pkgs)
         str/split-lines
         (map (fn [line] (str/split line #"\s+")))
         (filter #(= "install" (second %)))
         (map first)
         set)))

(defn installed?
  "Are the given debian packages, or singular package, installed on the current
  system?"
  [pkg-or-pkgs]
  (let [pkgs (if (coll? pkg-or-pkgs) pkg-or-pkgs (list pkg-or-pkgs))]
    (every? (installed pkgs) (map name pkgs))))

(defn installed-version
  "Given a package name, determines the installed version of that package, or
  nil if it is not installed."
  [pkg]
  (->> (c/exec :apt-cache :policy (name pkg))
       (re-find #"Installed: ([^\s]+)")
       second))

(defn install
  "Ensure the given packages are installed. Can take a flat collection of
  packages, passed as symbols, strings, or keywords, or, alternatively, a map
  of packages to version strings."
  [pkgs]
  (if (map? pkgs)
    ; Install specific versions
    (dorun
      (for [[pkg version] pkgs]
        (when (not= version (installed-version pkg))
          (c/exec :apt-get :install :-y (str (name pkg) "=" version)))))

    ; Install any version
    (let [pkgs    (set (map name pkgs))
          missing (set/difference pkgs (installed pkgs))]
      (when-not (empty? missing)
        (c/su
          (apply c/exec :apt-get :install :-y missing))))))

(def os
  (reify os/OS
    (setup! [_ test node]
      (info node "setting up debian")

      (setup-hostfile!)

      (c/su
        ; Packages!
        (install [:wget
                  :curl
                  :unzip
                  :iptables
                  :logrotate]))

      (meh (net/heal)))

    (teardown! [_ test node])))
