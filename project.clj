(defproject shadowfax "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]

                 [org.apache.hadoop/hadoop-client "2.0.0-cdh4.3.0"]
                 [org.apache.hbase/hbase "0.94.6-cdh4.3.0"]
                 [org.apache.oozie/oozie-client "3.3.2-cdh4.3.0"]

                 [com.salesforce/phoenix "2.0.0"]

                 [clj-time "0.6.0"]

                 [org.clojure/java.classpath "0.2.0"]

                 [org.slf4j/slf4j-api "1.6.4"]
                 [org.slf4j/slf4j-log4j12 "1.6.4"]]

  :plugins [[lein-swank "1.4.5"]]
  ;; :aot :all
  :repositories {
                 "cloudera-repos" "https://repository.cloudera.com/artifactory/cloudera-repos/"
                 "phoenix-github" "https://raw.github.com/forcedotcom/phoenix/maven-artifacts/releases"})
