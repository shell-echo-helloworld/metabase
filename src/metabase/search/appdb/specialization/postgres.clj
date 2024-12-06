(ns metabase.search.appdb.specialization.postgres
  (:require
   [clojure.string :as str]
   [metabase.search.appdb.specialization.api :as specialization]
   [metabase.util :as u]
   [toucan2.core :as t2]))

(def ^:private tsv-language "english")

(defmethod specialization/table-schema :postgres []
  [[:id :bigint [:primary-key] [:raw "GENERATED BY DEFAULT AS IDENTITY"]]
   ;; entity
   [:model_id :int :not-null]
   [:model [:varchar 254] :not-null]                        ;; TODO We could shrink this to just what we need.
   [:name :text :not-null]
   ;; search
   [:search_vector :tsvector :not-null]
   [:with_native_query_vector :tsvector :not-null]
   ;; results
   [:display_data :text :not-null]
   [:legacy_input :text :not-null]
   ;; scoring related
   [:dashboardcard_count :int]
   [:last_viewed_at :timestamp-with-time-zone]
   [:official_collection :boolean]
   [:pinned :boolean]
   [:verified :boolean]
   [:view_count :int]
   ;; permission related entities
   [:collection_id :int]
   [:database_id :int]
   ;; filter related
   [:archived :boolean :not-null [:default false]]
   [:creator_id :int]
   [:last_edited_at :timestamp-with-time-zone]
   [:last_editor_id :int]
   [:model_created_at :timestamp-with-time-zone]
   [:model_updated_at :timestamp-with-time-zone]
   ;; useful for tracking the speed and age of the index
   [:created_at :timestamp-with-time-zone
    [:default [:raw "CURRENT_TIMESTAMP"]]
    :not-null]
   [:updated_at :timestamp-with-time-zone :not-null]])

;; TODO I strongly suspect that there are more indexes that would help performance, we should examine EXPLAIN.
;; Another idea to try, is using a tsvector for all the non-range filter fields.
(defmethod specialization/post-create-statements :postgres [prefix table-name]
  (mapv
   (fn [template] (format template prefix table-name))
   ["CREATE UNIQUE INDEX IF NOT EXISTS %s_identity_idx ON %s (model, model_id)"
    "CREATE INDEX IF NOT EXISTS %s_tsvector_idx ON %s USING gin (search_vector)"
    "CREATE INDEX IF NOT EXISTS %s_native_tsvector_idx ON %s USING gin (with_native_query_vector)"
    ;; Spam all the indexes for now, let's see if they get used on Stats / Ephemeral envs.
    "CREATE INDEX IF NOT EXISTS %s_model_archived_idx ON %s (model, archived)"
    "CREATE INDEX IF NOT EXISTS %s_archived_idx ON %s (archived)"]))

(defmethod specialization/upsert! :postgres [table entry]
  (t2/query
   {:insert-into   table
    :values        [entry]
    :on-conflict   [:model :model_id]
    :do-update-set entry}))

(defmethod specialization/batch-upsert! :postgres [table entries]
  (when (seq entries)
    (t2/query
     ;; The cost of dynamically calculating these keys should be small compared to the IO cost, so unoptimized.
     (let [update-keys (vec (disj (set (keys (first entries))) :id :model :model_id))
           excluded-kw (fn [column] (keyword (str "excluded." (name column))))]
       {:insert-into   table
        :values        entries
        :on-conflict   [:model :model_id]
        :do-update-set (zipmap update-keys (map excluded-kw update-keys))}))))

(defn- quote* [s]
  (str "'" (str/replace s "'" "''") "'"))

(defn- process-phrase [word-or-phrase]
  ;; a phrase is quoted even if the closing quotation mark has not been typed yet
  (cond
    ;; trailing quotation mark
    (= word-or-phrase "\"") nil
    ;; quoted phrases must be matched sequentially
    (str/starts-with? word-or-phrase "\"")
    (as-> word-or-phrase <>
      ;; remove the quote mark(s)
      (str/replace <> #"^\"|\"$" "")
      (str/trim <>)
      (str/split <> #"\s+")
      (map quote* <>)
      (str/join " <-> " <>))

    ;; negation
    (str/starts-with? word-or-phrase "-")
    (str "!" (quote* (subs word-or-phrase 1)))

    ;; just a regular word
    :else
    (quote* word-or-phrase)))

(defn- split-preserving-quotes
  "Break up the words in the search input, preserving quoted and partially quoted segments."
  [s]
  (re-seq #"\"[^\"]*(?:\"|$)|[^\s\"]+|\s+" (u/lower-case-en s)))

(defn- process-clause [words-and-phrases]
  (->> words-and-phrases
       (remove #{"and"})
       (map process-phrase)
       (remove str/blank?)
       (str/join " & ")))

(defn- complete-last-word
  "Add wildcards at the end of the final word, so that we match ts completions."
  [expression]
  (str/replace expression #"(\S+)(?=\s*$)" "$1:*"))

(defn- to-tsquery-expr
  "Given the user input, construct a query in the Postgres tsvector query language."
  [input]
  (str
   (when input
     (let [trimmed        (str/trim input)
           complete?      (not (str/ends-with? trimmed "\""))
           ;; TODO also only complete if the :context is appropriate
           maybe-complete (if complete? complete-last-word identity)]
       (->> (split-preserving-quotes trimmed)
            (remove str/blank?)
            (partition-by #{"or"})
            (remove #(= (first %) "or"))
            (map process-clause)
            (str/join " | ")
            maybe-complete)))))

(defmethod specialization/base-query :postgres
  [active-table search-term search-ctx select-items]
  {:select select-items
   :from   [[active-table :search_index]]
   ;; Using a join allows us to share the query expression between our SELECT and WHERE clauses.
   :join   [[[:raw "to_tsquery('"
              tsv-language "', "
              [:lift (to-tsquery-expr search-term)] ")"]
             :query] [:= 1 1]]
   :where  (if (str/blank? search-term)
             [:= [:inline 1] [:inline 1]]
             [:raw
              (str
               (if (:search-native-query search-ctx)
                 "with_native_query_vector"
                 "search_vector")
               " @@ query")])})

(defmethod specialization/extra-entry-fields :postgres [entity]
  {:search_vector
   [:||
    [:setweight [:to_tsvector [:inline tsv-language] [:cast (:name entity) :text]]
     [:inline "A"]]
    [:setweight [:to_tsvector
                 [:inline tsv-language]
                 [:cast
                  (:searchable_text entity "")
                  :text]]
     [:inline "B"]]]

   :with_native_query_vector
   [:||
    [:setweight [:to_tsvector [:inline tsv-language] [:cast (:name entity) :text]]
     [:inline "A"]]
    [:setweight [:to_tsvector
                 [:inline tsv-language]
                 [:cast
                  (str (:searchable_text entity) " " (:native_query entity))
                  :text]]
     [:inline "B"]]]})
