(ns metabase.driver.presto-test
  (:require [expectations :refer :all]
            [metabase.driver :as driver]
            [metabase.driver.generic-sql :as sql]
            [metabase.models
             [field :refer [Field]]
             [table :refer [Table] :as table]]
            [metabase.test
             [data :as data]
             [util :refer [resolve-private-vars] :as tu]]
            [metabase.test.data.datasets :as datasets]
            [toucan.db :as db])
  (:import metabase.driver.presto.PrestoDriver))

(resolve-private-vars metabase.driver.presto details->uri details->request parse-presto-results quote-name quote+combine-names rename-duplicates apply-page)

;;; HELPERS

(expect
  "http://localhost:8080/"
  (details->uri {:host "localhost", :port 8080, :ssl false} "/"))

(expect
  "https://localhost:8443/"
  (details->uri {:host "localhost", :port 8443, :ssl true} "/"))

(expect
  "http://localhost:8080/v1/statement"
  (details->uri {:host "localhost", :port 8080, :ssl false} "/v1/statement"))

(expect
  {:headers {"X-Presto-Source" "metabase"
             "X-Presto-User"   "user"}}
  (details->request {:user "user"}))

(expect
  {:headers    {"X-Presto-Source" "metabase"
                "X-Presto-User"   "user"}
   :basic-auth ["user" "test"]}
  (details->request {:user "user", :password "test"}))

(expect
  {:headers {"X-Presto-Source"    "metabase"
             "X-Presto-User"      "user"
             "X-Presto-Catalog"   "test_data"
             "X-Presto-Time-Zone" "America/Toronto"}}
  (details->request {:user "user", :catalog "test_data", :report-timezone "America/Toronto"}))

(expect
  [["2017-04-03"
    #inst "2017-04-03T14:19:17.417000000-00:00"
    #inst "2017-04-03T10:19:17.417000000-00:00"
    3.1416M
    "test"]]
  (parse-presto-results nil
                        [{:type "date"} {:type "timestamp with time zone"} {:type "timestamp"} {:type "decimal(10,4)"} {:type "varchar(255)"}]
                        [["2017-04-03", "2017-04-03 10:19:17.417 America/Toronto", "2017-04-03 10:19:17.417", "3.1416", "test"]]))

(expect
  [[0, false, "", nil]]
  (parse-presto-results nil
                        [{:type "integer"} {:type "boolean"} {:type "varchar(255)"} {:type "date"}]
                        [[0, false, "", nil]]))

(expect
  "\"weird.table\"\" name\""
  (quote-name "weird.table\" name"))

(expect
  "\"weird . \"\"schema\".\"weird.table\"\" name\""
  (quote+combine-names "weird . \"schema" "weird.table\" name"))

(expect
  ["name" "count" "count_2" "sum", "sum_2", "sum_3"]
  (rename-duplicates ["name" "count" "count" "sum" "sum" "sum"]))

;; DESCRIBE-DATABASE
(datasets/expect-with-engine :presto
  {:tables #{{:name "categories" :schema "default"}
             {:name "venues"     :schema "default"}
             {:name "checkins"   :schema "default"}
             {:name "users"      :schema "default"}}}
  (driver/describe-database (PrestoDriver.) (data/db)))

;; DESCRIBE-TABLE
(datasets/expect-with-engine :presto
  {:name   "venues"
   :schema "default"
   :fields #{{:name      "name",
              :base-type :type/Text}
             {:name      "latitude"
              :base-type :type/Float}
             {:name      "longitude"
              :base-type :type/Float}
             {:name      "price"
              :base-type :type/Integer}
             {:name      "category_id"
              :base-type :type/Integer}
             {:name      "id"
              :base-type :type/Integer}}}
  (driver/describe-table (PrestoDriver.) (data/db) (db/select-one 'Table :id (data/id :venues))))

;;; TABLE-ROWS-SAMPLE
(datasets/expect-with-engine :presto
  [["Red Medicine"]
   ["Stout Burgers & Beers"]
   ["The Apple Pan"]
   ["Wurstküche"]
   ["Brite Spot Family Restaurant"]]
  (take 5 (driver/table-rows-sample (Table (data/id :venues))
            [(Field (data/id :venues :name))])))


;;; APPLY-PAGE
(expect
  {:select ["name" "id"]
   :from   [{:select   [[:default.categories.name "name"] [:default.categories.id "id"] [{:s "row_number() OVER (ORDER BY \"default\".\"categories\".\"id\" ASC)"} :__rownum__]]
             :from     [:default.categories]
             :order-by [[:default.categories.id :asc]]}]
   :where  [:> :__rownum__ 5]
   :limit  5}
  (apply-page {:select   [[:default.categories.name "name"] [:default.categories.id "id"]]
               :from     [:default.categories]
               :order-by [[:default.categories.id :asc]]}
              {:page {:page  2
                      :items 5}}))

(expect
  #"com.jcraft.jsch.JSchException:"
  (try
    (let [engine :presto
      details {:ssl false,
               :password "changeme",
               :tunnel-host "localhost",
               :tunnel-pass "BOGUS-BOGUS",
               :catalog "BOGUS"
               :host "localhost",
               :tunnel-enabled true,
               :tunnel-port 22,
               :tunnel-user "bogus"}]
      (driver/can-connect-with-details? engine details :rethrow-exceptions))
       (catch Exception e
         (.getMessage e))))

(datasets/expect-with-engine :presto
  "UTC"
  (tu/db-timezone-id))
