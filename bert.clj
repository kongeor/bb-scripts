#!/usr/bin/bb

(require '[clojure.pprint :as pp])
(require '[clojure.java.shell :as sh])
(require '[babashka.fs :as fs])

(fs/create-dirs "foo/bar/baz")

(defn get-series-url [id]
  (str "https://api.app.ertflix.gr/v1/Tile/GetSeriesDetails?platformCodename=www&id=" id "&$headers=%7B%22X-Api-Date-Format%22:%22iso%22,%22X-Api-Camel-Case%22:true%7D"))

(comment
  (get-series-url "ser.158278"))

(let [data     (slurp (get-series-url "ser.158278"))
      episodes (-> (json/parse-string data true)
                 :episodeGroups
                 )]
  episodes
  #_(map #(select-keys % [:episodeNumber :subtitle :codename]) episodes))

(defn get-episodes
  ([id]
   (get-episodes id 0))
  ([id season]
   (let [data     (slurp (get-series-url id))
         episodes (-> (json/parse-string data true)
                    :episodeGroups
                    (nth season)
                    :episodes)]
     (map #(select-keys % [:episodeNumber :subtitle :codename]) episodes))))


(comment
  (get-episodes "ser.158278" 1))

(defn get-episode-url [codename]
  (let [data      (slurp (str "https://api.app.ertflix.gr/v1/Player/AcquireContent?platformCodename=www&deviceKey=8a90c07ca7d8cad07fd49ba5e6454ee1&codename=" codename))
        data-json (json/parse-string data true)]
    (-> data-json
      :MediaFiles
      first
      :Formats
      first
      :Url)))


(comment
  (get-episode-url "arithmokubakia-1"))

(defn get-series-id [url]
  (let [page (slurp url)
        ;; extract initial state from json blob
        matcher (re-matcher #".*___INITIAL_STATE__ =(.*)</script><script>var ___CLIENT___" page)]
    (re-find matcher)
    (let [groups (re-groups matcher)
          initial-state-json (json/parse-string (second groups) true)]
      (-> initial-state-json
        :tiles
        :tilesById
        first
        second
        :series
        :id))))

(comment
  (get-series-id "https://www.ertflix.gr/vod/vod.191802-arithmokubakia-35"))

(defn save-series-data-file [url season]
  (let [id           (get-series-id url)
        episodes     (get-episodes id season)
        episode-data (->> episodes
                       (map #(assoc % :url (get-episode-url (:codename %)))))]
    (spit "episodes.edn" (with-out-str (pp/pprint episode-data)))))

(comment
  (save-series-data-file "https://www.ertflix.gr/vod/vod.191802-arithmokubakia-35"))

(defn download-episodes [dir]
  (let [episodes (read-string (slurp "episodes.edn"))]
    (doseq [e episodes]
      (let [episode-name (str/trim (:subtitle e))
            _ (fs/create-dirs dir)
            file-name (str dir File/separator (format "%02d" (:episodeNumber e)) " - " episode-name ".%(ext)s")]
        (println "Downloading" file-name)
        (sh/sh "yt-dlp" (:url e) "-o" file-name)))))

(comment
  (format "%02d" 15))

(comment
  (download-episodes))

(defmulti exec-cmd (fn [cmd args]
                     cmd))

(defmethod exec-cmd "list" [_ [url season]]
  (let [series-id (get-series-id url)
        episodes (get-episodes series-id (parse-long season))]
    (pp/pprint episodes)))

(defmethod exec-cmd "download" [_ [url season dir]]
  (let [dir' (or dir "videos")]
    (do
      (println "Saving series urls for " url)
      (save-series-data-file url (parse-long season))
      (println "Downloading series data")
      (download-episodes dir'))))

(defmethod exec-cmd "help" [& _]
  (println "bb bert.clj list-episodes url 0"))

(comment
  (exec-cmd "list" ["asdf" "zxcv"]))

(defn run [[cmd & args]]
  (exec-cmd cmd args)
  #_(if-let [url (str (:url opts))]
    (do
      (println "Saving series urls for " url)
      (save-series-data-file url)
      (println "Downloading series data")
      (download-episodes))
    (do
      (println "No url provided")
      (System/exit 1))))

(if-let [args *command-line-args*]
  (run args))