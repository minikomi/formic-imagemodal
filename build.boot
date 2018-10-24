(def project 'co.poyo/formic-imagemodal)
(def version "0.1.0-SNAPSHOT")

(set-env! :resource-paths #{"src"}
          :dependencies   '[[org.clojure/clojure "1.9.0"]
                            [org.clojure/clojurescript "1.10.238"]
                            [adzerk/bootlaces "0.1.13"]
                            [cljsjs/dropzone "5.5.0-1"]
                            [reagent "0.8.0"]
                            [cljs-ajax "0.7.5"]
                            ])

(require '[adzerk.bootlaces :refer :all])

(bootlaces! version)

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
