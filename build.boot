(def project 'co.poyo/formic-imagemodal)
(def version "0.1.0-SNAPSHOT")

(set-env! :resource-paths #{"src/cljs"}
          :dependencies   '[[org.clojure/clojure "1.9.0"]
                            [org.clojure/clojurescript "1.10.238"]
                            [cljsjs/dropzone "4.3.0-0"]
                            [reagent "0.8.0"]
                            [cljs-ajax "0.7.3"]
                            [funcool/struct "1.2.0"]])

(task-options!
 pom {:project     project
      :version     version
      :description "Frontend / Backend tools for creating forms declaritively"
      :url         ""
      :scm         {:url ""}
      :license     {"Eclipse Public License"
                    "http://www.eclipse.org/legal/epl-v10.html"}})

(deftask build
  "Build and install the project locally."
  []
  (comp (pom) (jar) (install)))
