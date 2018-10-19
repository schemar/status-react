(ns status-im.chat.models.message-content
  (:require [clojure.string :as string]
            [status-im.constants :as constants]))

(def stylings {:bold   constants/regx-bold
               :italic constants/regx-italic})

(def ^:private actions [[:link    constants/regx-url]
                        [:tag     constants/regx-tag]
                        [:mention constants/regx-mention]])

(def ^:private styling-characters #"\*|~")

(defn- blank-string [size]
  (apply str (take size (repeat " "))))

(defn- clear-ranges [ranges input]
  (reduce (fn [acc [start end]]
            (apply str (subs acc 0 start) (blank-string (- end start)) (subs acc end)))
          input ranges))

(defn- query-regex [regex content]
  (loop [input   content
         matches []
         offset  0]
    (if-let [match (.exec regex input)]
      (let [match-value    (aget match 0)
            match-size     (count match-value)
            relative-index (.-index match)
            start-index    (+ offset relative-index)
            end-index      (+ start-index match-size)]
        (recur (apply str (drop (+ relative-index match-size) input))
               (conj matches [start-index end-index])
               end-index))
      (seq matches))))

(defn- right-to-left-text? [text]
  (and (seq text)
       (re-matches constants/regx-rtl-characters (first text))))

(defn- should-collapse? [text]
  (or (<= constants/chars-collapse-threshold (count text))
      (<= constants/lines-collapse-threshold (inc (count (query-regex #"\n" text))))))

(defn enrich-content
  "Enriches message content with `:metadata` and `:rtl?` information.
  Metadata map keys can by any of the `:link`, `:tag`, `:mention` actions
  or `:bold` and `:italic` stylings.
  Value for each key is sequence of tuples representing ranges in original
  `:text` content. "
  [{:keys [text] :as content}]
  (let [[cleared-text actions-metadata] (reduce (fn [[text metadata] [type regex]]
                                                  (if-let [matches (query-regex regex text)]
                                                    [(clear-ranges matches text) (assoc metadata type matches)]
                                                    [text metadata]))
                                                [text {}]
                                                actions)
        metadata                        (reduce-kv (fn [metadata type regex]
                                                     (if-let [matches (query-regex regex cleared-text)]
                                                       (assoc metadata type matches)
                                                       metadata))
                                                   actions-metadata
                                                   stylings)]
    (cond-> content
      (seq metadata) (assoc :metadata metadata)
      (right-to-left-text? text) (assoc :rtl? true)
      (should-collapse? text) (assoc :should-collapse? true))))

(defn- sorted-ranges [{:keys [metadata text]} metadata-keys]
  (->> (if metadata-keys
         (select-keys metadata metadata-keys)
         metadata)
       (reduce-kv (fn [acc type ranges]
                    (reduce #(assoc %1 %2 type) acc ranges))
                  {})
       (sort-by (comp (juxt first second) first))
       (cons [[0 (count text)] :text])))

(defn- last-index [result]
  (or (some-> result peek :end) 0))

(defn- start [[[start]]] start)

(defn- end [[[_ end]]] end)

(defn- kind [[_ kind]] kind)

(defn- result-record [start end path]
  {:start start
   :end   end
   :kind  (into #{} (map kind) path)})

(defn build-render-recipe
  "Builds render recipe from message text and metadata, can be used by render code
  by simply iterating over it and paying attention to `:kind` set for each segment of text.
  Optional in optional 2 arity version, you can pass collection of keys determining which
  metadata to include in the render recipe (all of them by default)."
  ([content]
   (build-render-recipe content nil))
  ([{:keys [text metadata] :as content} metadata-keys]
   (letfn [(builder [[top :as stack] [input & rest-inputs :as inputs] result]
             (if (seq input)
               (cond
                 ;; input is child of the top
                 (and (<= (start input) (end top))
                      (<= (end input) (end top)))
                 (recur (conj stack input) rest-inputs
                        (conj result (result-record (last-index result) (start input) stack)))
                 ;; input overlaps top, it's neither child, nor sibling, discard input
                 (and (>= (start input) (start top))
                      (<= (start input) (end top)))
                 (recur stack rest-inputs result)
                 ;; the only remaining possibility, input is next sibling to top
                 :else
                 (recur (rest stack) inputs
                        (conj result (result-record (last-index result) (end top) stack))))
               ;; inputs consumed, unwind stack
               (loop [[top & rest-stack :as stack] stack
                      result                       result]
                 (if top
                   (recur rest-stack
                          (conj result (result-record (last-index result) (end top) stack)))
                   result))))]
     (when metadata
       (let [[head & tail] (sorted-ranges content metadata-keys)]
         (->> (builder (list head) tail [])
              (keep (fn [{:keys [start end kind]}]
                      (let [text-content (-> (subs text start end) ;; select text chunk & remove styling chars
                                             (string/replace styling-characters ""))]
                        (when (seq text-content) ;; filter out empty text chunks
                          [text-content kind]))))))))))

(defn emoji-only-content?
  "Determines if text is just an emoji"
  [{:keys [text response-to]}]
  (and (not response-to)
       (string? text)
       (re-matches constants/regx-emoji text)))
