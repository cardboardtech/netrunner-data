(ns nr-data.scratch
  (:require
   [clojure.string :as str]
   [nr-data.combine :refer [generate-formats load-cards load-data load-edn-from-dir load-sets merge-sets-and-cards]]
   [nr-data.utils :refer [cards->map normalize-text slugify]]
   [semantic-csv.core :as sc]
   [ubergraph.core :as uber]
   [zprint.core :as zp]))

(defn mwls [] (load-data "mwls" {:id :code}))
(defn sides [] (load-data "sides"))
(defn factions [] (load-data "factions"))
(defn types [] (load-data "types"))
(defn subtypes [] (load-data "subtypes"))
(defn formats [] (load-data "formats"))
(defn cycles [] (load-data "cycles"))
(defn sets [] (load-sets (cycles)))
(defn combined-card [] (load-cards (sides) (factions) (types) (subtypes) (sets) (formats) (mwls)))

(defn set-cards [] (load-edn-from-dir "edn/set-cards"))
(defn raw-cards [] (cards->map :id (load-edn-from-dir "edn/cards")))
(defn cards [] (merge-sets-and-cards (set-cards) (raw-cards)))
(defn card->formats [] (generate-formats (sets) (cards) (formats) (mwls)))

(defn ability-costs [card]
  (when (seq (:text card))
    (->> (:text card)
         (str/split-lines)
         (filter #(str/includes? % ":"))
         (map str/lower-case)
         (map #(str/replace % #"<strong>" ""))
         (map #(str/replace % #"<\/strong>" ""))
         (map #(str/replace % #"\d\[credit]" "[credit]"))
         (map #(str/replace % #"x\[credit]" "[credit]"))
         (map #(str/replace % #"\d " ""))
         (map #(str/replace % #"return .*? to your grip" "return this card to your grip"))
         (map #(str/replace % #"remove .*? from the game" "remove this card from the game"))
         (map #(str/replace % #"suffer .*? damage" "suffer damage"))
         (map #(str/replace % #"tokens" "token"))
         (map #(str/replace % #"counters" "counter"))
         (map #(str/split % #":"))
         (map first)
         (map str/trim)
         (map #(str/split % #","))
         (map #(map str/trim %))
         (filter #(< 1 (count %)))
         seq)))

(defn multi-costs [cards]
  (mapcat identity (keep ability-costs cards)))

(defn make-directed-graph [graph costs]
  (reduce
    (fn [graph [c1 c2]] (uber/add-directed-edges graph [c1 c2]))
    graph
    (into #{} (for [cost costs
                    [c1 c2] (partition 2 1 cost)
                    :when (not= c1 c2)]
                [c1 c2]))))

(defn spend-vs-pay [card]
  (when (seq (:text card))
    (->> (:text card)
         (str/lower-case)
         (re-find #"spend.*?\[click].*?\[credit]"))))

(defn quantify [n s]
  (if (or (= "1" n)
          (= "-1" n))
    (str n " " s)
    (str n " " s "s")))

(defn clean-text
  [card]
  (some-> (not-empty (:text card))
          (str/replace #"</?(strong|trace|errata|em|i|ul)>" "")
          (str/replace #"</li>" "")
          (str/replace #"<li>" " * ")
          (str/replace #"\n" " ")
          (str/replace "–" "-")
          (str/replace "→" "->")
          (str/replace #"\[click\]\[click\]\[click\]" "click click click")
          (str/replace #"\[click\]\[click\]" "click click")
          (str/replace #"\[click\]" "click")
          (str/replace #"(\d+|X)\s*\[credit\]" #(quantify (%1 1) "credit"))
          (str/replace #"(\d+|X)\s*\[recurring-credit]" #(quantify (%1 1) "recurring credit"))
          (str/replace #"(\d+|X)\s*\[mu]" "$1 mu")
          (str/replace #"(\d+|X)\s*\[link]" "$1 link")
          (str/replace #"\[link\]" "link")
          (str/replace #"\[subroutine]\s*" "Subroutine ")
          (str/replace #"\[trash]" "trash")
          (str/replace #"\[interrupt]" "Interrupt")
          (str/replace #"\[(anarch|criminal|shaper)]" "$1")
          (str/replace #"\[(haas-bioroid|jinteki|nbn|weyland-consortium)]" "$1")))

(defn add-stripped-text [card]
  (if-let [plain-text (normalize-text (clean-text card))]
    (assoc card :stripped-text plain-text)
    card))

(defn add-stripped-title [card]
  (->> (:title card)
       (normalize-text)
       (assoc card :stripped-title)))

(defn add-stripped-card-text [cards]
  (->> cards
       (map add-stripped-text)
       (map add-stripped-title)))

(defn fix-arrows [card]
  (if (:text card)
    (assoc card :text (-> (:text card)
                          (str/replace " > " " → ")
                          (str/replace " -> " " → ")
                          (str/replace "[interrupt] -> " "[interrupt] – ")
                          (str/replace "[interrupt] → " "[interrupt] – ")
                          ))
    card))

(defn clean-card-text [cards]
  (->> cards
       (map fix-arrows)
       (add-stripped-card-text)))

(defn save-cards [cards]
  (doseq [card (clean-card-text cards)]
    (spit (str "edn/cards/" (:id card) ".edn")
          (str (zp/zprint-str card) "\n"))))

(def c (vals (raw-cards)))

(comment
  (println (keys (into {} c)))
  (save-cards (vals (raw-cards)))
  ; (convert-to-json)
  )

(defn convert-tags [text]
  (-> text
      (str/replace "{b}" "<strong>")
      (str/replace "{/b}" "</strong>")
      (str/replace "{i}" "<em>")
      (str/replace "{/i}" "</em>")
      (str/replace "{click}" "[click]")
      (str/replace "{c}" "[credit]")
      (str/replace "{interrupt}" "[interrupt]")
      (str/replace "{link}" "[link]")
      (str/replace "{mu}" "[mu]")
      (str/replace "{MU}" "[mu]")
      (str/replace "{recurring-credit}" "[recurring-credit]")
      (str/replace "{sub}" "[subroutine]")
      (str/replace "{trash}" "[trash]")
      (str/replace #"\{(haas-bioroid|jinteki|nbn|weyland-consortium)}" "[$1]")
      (str/replace "\\n" "\n")))

(defn load-oracle-csv []
  (->> (sc/slurp-csv "oracle.csv")
       (map #(-> %
                 (assoc :name (slugify (:CardName %)))
                 (dissoc :CardName)))
       (map #(-> %
                 (assoc :text (convert-tags (:CurrentOfficialText %)))
                 (dissoc :CurrentOfficialText)))
       (map (juxt :name :text))
       (into {})))

(defn save-oracle-cards [oracle-cards raw-cards]
  (doseq [[id text] oracle-cards
          :let [card (-> (get raw-cards id)
                         (assoc :text text)
                         (add-stripped-text))]]
    (if (:id card)
      (spit (str "edn/cards/" (:id card) ".edn")
            (str (zp/zprint-str card) "\n"))
      (prn card))))

(comment
  (load-oracle-csv)
  (raw-cards)
  (save-oracle-cards (load-oracle-csv) (raw-cards))
  )
